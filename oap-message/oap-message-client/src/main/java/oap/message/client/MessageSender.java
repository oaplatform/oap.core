/*
 * The MIT License (MIT)
 *
 * Copyright (c) Open Application Platform Authors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package oap.message.client;

import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tags;
import lombok.SneakyThrows;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import oap.LogConsolidated;
import oap.concurrent.Executors;
import oap.concurrent.scheduler.Scheduled;
import oap.concurrent.scheduler.Scheduler;
import oap.http.Client;
import oap.io.Closeables;
import oap.io.Files;
import oap.io.content.ContentReader;
import oap.io.content.ContentWriter;
import oap.message.MessageProtocol;
import oap.message.MessageStatus;
import oap.message.client.MessageAvailabilityReport.State;
import oap.util.ByteSequence;
import oap.util.Cuid;
import oap.util.Dates;
import oap.util.FastByteArrayOutputStream;
import oap.util.Pair;
import oap.util.Throwables;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FilenameUtils;
import org.joda.time.DateTimeUtils;
import org.slf4j.event.Level;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.UnknownHostException;
import java.nio.file.DirectoryStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@ToString
public class MessageSender implements Closeable, AutoCloseable {
    private static final Pair<MessageStatus, Short> STATUS_OK = Pair.__( MessageStatus.OK, MessageProtocol.STATUS_OK );

    private final Object syncDiskLock = new Object();
    private final String host;
    private final int port;
    private final Path directory;
    private final MessageNoRetryStrategy messageNoRetryStrategy;
    private final long clientId = Cuid.UNIQUE.nextLong();
    private final Messages messages = new Messages();
    private final ConcurrentMap<Byte, Pair<MessageStatus, Short>> lastStatus = new ConcurrentHashMap<>();
    private final String messageUrl;
    private final long memorySyncPeriod;
    public String uniqueName = Cuid.UNIQUE.next();
    public long storageLockExpiration = Dates.h( 1 );
    public int poolSize = 4;
    public long diskSyncPeriod = Dates.m( 1 );
    public long globalIoRetryTimeout = Dates.s( 1 );
    public long retryTimeout = Dates.s( 1 );
    public long keepAliveDuration = Dates.d( 30 );
    protected long timeout = Dates.s( 5 );
    protected long connectionTimeout = Dates.s( 30 );
    private volatile boolean closed = false;
    private Scheduled diskSyncScheduler;
    private boolean networkAvailable = true;
    private Client httpClient;
    private ExecutorService executor;
    private long ioExceptionStartRetryTimeout = -1;

    public MessageSender( String host, int port, String httpPrefix, Path persistenceDirectory, long memorySyncPeriod ) {
        this( host, port, httpPrefix, persistenceDirectory, memorySyncPeriod, MessageNoRetryStrategy.DROP );
    }

    public MessageSender( String host, int port, String httpPrefix, Path persistenceDirectory,
                          long memorySyncPeriod, MessageNoRetryStrategy messageNoRetryStrategy ) {
        this.host = host;
        this.port = port;
        this.memorySyncPeriod = memorySyncPeriod;
        messageUrl = "http://" + host + ":" + port + httpPrefix;
        this.directory = persistenceDirectory;
        this.messageNoRetryStrategy = messageNoRetryStrategy;

        Metrics.gaugeCollectionSize( "message_count", Tags.of( "host", host, "type", "ready", "port", String.valueOf( port ) ), messages.ready );
        Metrics.gaugeCollectionSize( "message_count", Tags.of( "host", host, "type", "retry", "port", String.valueOf( port ) ), messages.retry );
        Metrics.gaugeMapSize( "message_count", Tags.of( "host", host, "type", "inprogress", "port", String.valueOf( port ) ), messages.inProgress );
    }

    public static Path lock( String uniqueName, Path file, long storageLockExpiration ) {
        var lockFile = Paths.get( FilenameUtils.removeExtension( file.toString() ) + ".lock" );

        if( Files.createFile( lockFile ) != null ) return lockFile;
        if( storageLockExpiration <= 0 ) return null;

        log.trace( "[{}] lock found {}, expiration = {}", uniqueName, lockFile,
            Dates.durationToString( Files.getLastModifiedTime( lockFile ) + storageLockExpiration - DateTimeUtils.currentTimeMillis() ) );

        return Files.getLastModifiedTime( lockFile ) + storageLockExpiration < DateTimeUtils.currentTimeMillis()
            ? lockFile : null;
    }

    public final long getClientId() {
        return clientId;
    }

    public void start() {
        log.info( "[{}] message server messageUrl {} storage {} storageLockExpiration {}",
            uniqueName, messageUrl, directory, Dates.durationToString( storageLockExpiration ) );
        log.info( "[{}] connection timeout {} rw timeout {} pool size {} keepAliveDuration {}",
            uniqueName, Dates.durationToString( connectionTimeout ), Dates.durationToString( timeout ), poolSize,
            Dates.durationToString( keepAliveDuration ) );
        log.info( "[{}] retry timeout {} disk sync period '{}' memory sync period '{}'",
            uniqueName, Dates.durationToString( retryTimeout ), Dates.durationToString( diskSyncPeriod ), Dates.durationToString( memorySyncPeriod ) );
        log.info( "custom status = {}", MessageProtocol.printMapping() );

        executor = Executors.newFixedBlockingThreadPool(
            poolSize > 0 ? poolSize : Runtime.getRuntime().availableProcessors(),
            new ThreadFactoryBuilder().setNameFormat( uniqueName + "-%d" ).build() );

        Client.ClientBuilder clientBuilder = Client.custom().setConnectTimeout( connectionTimeout );

        if( poolSize > 0 ) {
            clientBuilder.setMaxConnPerRoute( poolSize ).setMaxConnTotal( poolSize );
        }

        httpClient = clientBuilder.build();

        if( diskSyncPeriod > 0 )
            diskSyncScheduler = Scheduler.scheduleWithFixedDelay( diskSyncPeriod, TimeUnit.MILLISECONDS, this::syncDisk );

        if( memorySyncPeriod > 0 )
            diskSyncScheduler = Scheduler.scheduleWithFixedDelay( memorySyncPeriod, TimeUnit.MILLISECONDS, this::syncMemory );
        log.info( "[{}] message server started", uniqueName );
    }

    public <T> MessageSender send( byte messageType, T data, ContentWriter<T> writer ) {
        return send( messageType, ( short ) 1, data, writer );
    }

    public <T> MessageSender send( byte messageType, short version, T data, ContentWriter<T> writer ) {
        byte[] bytes = writer.write( data );
        return send( messageType, version, bytes, 0, bytes.length );
    }

    public MessageSender send( byte messageType, byte[] data, int offset, int length ) {
        return send( messageType, ( short ) 1, data, offset, length );
    }

    public MessageSender send( byte messageType, short version, byte[] data, int offset, int length ) {
        Preconditions.checkNotNull( data );
        Preconditions.checkArgument( ( messageType & 0xFF ) <= 200, "reserved" );

        var md5 = DigestUtils.getMd5Digest().digest( data );
        var message = new Message( clientId, messageType, version, ByteSequence.of( md5 ), data, offset, length );
        messages.add( message );

        return this;
    }


    @Override
    public void close() {
        synchronized( syncDiskLock ) {
            closed = true;

            Closeables.close( diskSyncScheduler );
        }

        int count = 0;
        while( count < 100 && !messages.inProgress.isEmpty() ) {
            try {
                Thread.sleep( 100 );
                count++;
            } catch( InterruptedException e ) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        saveMessagesToDirectory( directory );
    }

    private void saveMessagesToDirectory( Path directory ) {
        while( true ) {
            var messageInfo = messages.poll( false );
            if( messageInfo == null ) {
                messageInfo = messages.pollInProgress();
            }
            if( messageInfo == null ) {
                Messages.RetryInfo retryInfo = messages.pollRetry();
                messageInfo = retryInfo != null ? retryInfo.messageInfo : null;
            }

            if( messageInfo == null ) break;
            Message message = messageInfo.message;

            var parentDirectory = directory
                .resolve( Long.toHexString( clientId ) )
                .resolve( String.valueOf( Byte.toUnsignedInt( message.messageType ) ) );
            var tmpMsgPath = parentDirectory.resolve( message.getHexMd5() + '-' + message.version + ".bin.tmp" );
            log.debug( "[{}] writing unsent message to {}", uniqueName, tmpMsgPath );
            try {
                Files.write( tmpMsgPath, message.data, ContentWriter.ofBytes() );
                var msgPath = parentDirectory.resolve( message.getHexMd5() + '-' + message.version + ".bin" );
                Files.rename( tmpMsgPath, msgPath );
            } catch( Exception e ) {
                log.error( "[{}] type: {}, md5: {}, data: {}",
                    uniqueName, message.messageType, message.getHexMd5(), message.getHexData(), e );
            }
        }
    }

    public MessageAvailabilityReport availabilityReport( byte messageType ) {
        var operational = networkAvailable
            && !closed
            && lastStatus.getOrDefault( messageType, STATUS_OK )._1 != MessageStatus.ERROR;
        return new MessageAvailabilityReport( operational ? State.OPERATIONAL : State.FAILED );
    }

    @SuppressWarnings( "checkstyle:OverloadMethodsDeclarationOrder" )
    public CompletableFuture<Messages.MessageInfo> send( Messages.MessageInfo messageInfo, long now ) {
        Message message = messageInfo.message;

        log.debug( "[{}] sending data [type = {}] to server...", uniqueName, MessageProtocol.messageTypeToString( message.messageType ) );

        return CompletableFuture.supplyAsync( () -> {
            Metrics.counter( "oap.messages", "type", MessageProtocol.messageTypeToString( message.messageType ), "status", "trysend" ).increment();

            try( FastByteArrayOutputStream buf = new FastByteArrayOutputStream();
                 DataOutputStream out = new DataOutputStream( buf ) ) {
                out.writeByte( message.messageType );
                out.writeShort( message.version );
                out.writeLong( message.clientId );

                out.write( message.md5.bytes );

                out.write( MessageProtocol.RESERVED, 0, MessageProtocol.RESERVED_LENGTH );
                out.writeInt( message.data.length );
                out.write( message.data );

                Client.Response response = httpClient.post( messageUrl, buf.array, 0, buf.length, timeout )
                    .orElseThrow( f -> f );

                if( response.code >= 300 || response.code < 200 ) {
                    throw new IOException( "Not OK (" + response.code + ") response code returned for url: " + messageUrl );
                }
                return onOkRespone( messageInfo, response, now );

            } catch( UnknownHostException e ) {
                processException( messageInfo, now, message, e, true );

                ioExceptionStartRetryTimeout = now;

                throw Throwables.propagate( e );
            } catch( Throwable e ) {
                processException( messageInfo, now, message, e, false );

                throw Throwables.propagate( e );
            }
        }, executor );
    }

    private void processException( Messages.MessageInfo messageInfo, long now, Message message, Throwable e, boolean globalRetryTimeout ) {
        Metrics.counter( "oap.messages",
            "type", MessageProtocol.messageTypeToString( message.messageType ),
            "status", "send_io_error" + ( globalRetryTimeout ? "_gr" : "" ) ).increment();
        LogConsolidated.log( log, Level.ERROR, Dates.s( 10 ), e.getMessage(), e );
        messages.retry( messageInfo, now + retryTimeout );
    }

    private Messages.MessageInfo onOkRespone( Messages.MessageInfo messageInfo, Client.Response response, long now ) {
        Message message = messageInfo.message;

        InputStream body = response.getInputStream();
        if( body == null ) {
            Metrics.counter( "oap.messages", "type", MessageProtocol.messageTypeToString( message.messageType ), "status", "io_error" ).increment();
            log.error( "[{}] unknown error (BODY == null)", uniqueName );
            lastStatus.put( message.messageType, Pair.__( MessageStatus.ERROR, ( short ) -1 ) );
            messages.retry( messageInfo, now + retryTimeout );
            return messageInfo;
        }

        try( var in = new DataInputStream( body ) ) {
            var version = in.readByte();
            if( version != MessageProtocol.PROTOCOL_VERSION_1 ) {
                log.error( "[{}] Version mismatch, expected: {}, received: {}", uniqueName, MessageProtocol.PROTOCOL_VERSION_1, version );
                throw new MessageException( "Version mismatch" );
            }
            in.readLong(); // clientId
            in.skipNBytes( MessageProtocol.MD5_LENGTH ); // digestionId
            in.skipNBytes( MessageProtocol.RESERVED_LENGTH );
            var status = in.readShort();

            log.trace( "[{}] sending done, server status: {}", uniqueName, MessageProtocol.messageStatusToString( status ) );

            MessageSender.this.networkAvailable = true;

            switch( status ) {
                case MessageProtocol.STATUS_ALREADY_WRITTEN -> {
                    log.trace( "[{}] already written {}", uniqueName, message.getHexMd5() );
                    Metrics.counter( "oap.messages", "type", MessageProtocol.messageTypeToString( message.messageType ), "status", "already_written" ).increment();
                    lastStatus.put( message.messageType, Pair.__( MessageStatus.ALREADY_WRITTEN, status ) );
                }
                case MessageProtocol.STATUS_OK -> {
                    Metrics.counter( "oap.messages", "type", MessageProtocol.messageTypeToString( message.messageType ), "status", "success" ).increment();
                    lastStatus.put( message.messageType, Pair.__( MessageStatus.OK, status ) );
                }
                case MessageProtocol.STATUS_UNKNOWN_ERROR -> {
                    Metrics.counter( "oap.messages", "type", MessageProtocol.messageTypeToString( message.messageType ), "status", "error" ).increment();
                    log.error( "[{}] unknown error", uniqueName );
                    lastStatus.put( message.messageType, Pair.__( MessageStatus.ERROR, status ) );
                    messages.retry( messageInfo, now + retryTimeout );
                }
                case MessageProtocol.STATUS_UNKNOWN_ERROR_NO_RETRY -> {
                    Metrics.counter( "oap.messages", "type", MessageProtocol.messageTypeToString( message.messageType ), "status", "error_no_retry" ).increment();
                    log.error( "[{}] unknown error -> no retry", uniqueName );
                    lastStatus.put( message.messageType, Pair.__( MessageStatus.ERROR, status ) );
                }
                case MessageProtocol.STATUS_UNKNOWN_MESSAGE_TYPE -> {
                    Metrics.counter( "oap.messages", "type", MessageProtocol.messageTypeToString( message.messageType ), "status", "unknown_message_type" ).increment();
                    log.error( "[{}] unknown message type: {}", uniqueName, status );
                    lastStatus.put( message.messageType, Pair.__( MessageStatus.ERROR, status ) );
                }
                default -> {
                    var clientStatus = MessageProtocol.getStatus( status );
                    if( clientStatus != null ) {
                        log.trace( "[{}] retry: {}", uniqueName, clientStatus );
                        Metrics.counter( "oap.messages", "type", MessageProtocol.messageTypeToString( message.messageType ), "status", "status_" + status + "(" + clientStatus + ")" ).increment();
                    } else {
                        Metrics.counter( "oap.messages", "type", MessageProtocol.messageTypeToString( message.messageType ), "status", "unknown_status" ).increment();
                        log.error( "[{}] unknown status: {}", uniqueName, status );
                    }
                    lastStatus.put( message.messageType, Pair.__( MessageStatus.ERROR, status ) );
                    messages.retry( messageInfo, now + retryTimeout );
                }
            }
        } catch( IOException e ) {
            throw new UncheckedIOException( e );
        }
        return messageInfo;
    }

    public void syncMemory() {
        syncMemory( -1 );
    }

    public void syncMemory( long timeoutMs ) {
        if( getReadyMessages() + getRetryMessages() + getInProgressMessages() > 0 )
            log.trace( "[{}] sync ready {} retry {} inprogress {} ...",
                uniqueName, getReadyMessages(), getRetryMessages(), getInProgressMessages() );

        long now = DateTimeUtils.currentTimeMillis();

        if( isGlobalIoRetryTimeout( now ) ) return;

        long period = currentPeriod( now );

        Messages.MessageInfo messageInfo = null;

        messages.retry();

        do {
            now = DateTimeUtils.currentTimeMillis();

            if( messageInfo != null ) {
                log.trace( "[{}] message {}...", uniqueName, messageInfo.message.md5 );
                var future = send( messageInfo, now );
                future.handle( ( mi, e ) -> {
                    messages.removeInProgress( mi );
                    log.trace( "[{}] message {}... done", uniqueName, mi.message.md5 );
                    return null;
                } );

                if( timeoutMs >= 0 ) {
                    try {
                        future.get( timeoutMs, TimeUnit.MILLISECONDS );
                    } catch( InterruptedException | TimeoutException | ExecutionException e ) {
                        log.error( e.getMessage(), e );
                    }
                }
            }

            if( isGlobalIoRetryTimeout( now ) ) {
                break;
            }

            var currentPeriod = currentPeriod( now );
            if( currentPeriod != period ) {
                messages.retry();
                period = currentPeriod;
            }

            messageInfo = messages.poll( true );
        } while( messageInfo != null );
    }

    private boolean isGlobalIoRetryTimeout( long now ) {
        return ioExceptionStartRetryTimeout > 0 && globalIoRetryTimeout > 0 && ioExceptionStartRetryTimeout + globalIoRetryTimeout > now;
    }

    private long currentPeriod( long time ) {
        return time / retryTimeout;
    }

    @SneakyThrows
    public MessageSender syncDisk() {
        if( closed ) return this;

        try( DirectoryStream<Path> clientIdStream = java.nio.file.Files.newDirectoryStream( directory ) ) {
            for( var clientIdPath : clientIdStream ) {
                if( !isValidClientId( clientIdPath ) ) {
                    log.warn( "invalid client id {}", clientIdPath );
                    Files.deleteSafely( clientIdPath );
                    continue;
                }

                var msgClientId = Long.parseLong( FilenameUtils.getName( clientIdPath.toString() ), 16 );
                try( DirectoryStream<Path> messageTypeStream = java.nio.file.Files.newDirectoryStream( clientIdPath ) ) {
                    for( var messageTypePath : messageTypeStream ) {
                        if( !isValidMessageType( messageTypePath ) ) {
                            log.warn( "invalid message type {}", messageTypePath );
                            Files.deleteSafely( messageTypePath );
                            continue;
                        }

                        var messageType = ( byte ) Integer.parseInt( FilenameUtils.getName( messageTypePath.toString() ) );

                        try( DirectoryStream<Path> messageStream = java.nio.file.Files.newDirectoryStream( messageTypePath ) ) {
                            for( var messagePath : messageStream ) {
                                if( !isValidMessage( messagePath ) ) {
                                    log.warn( "invalid message {}", messagePath );
                                    Files.deleteSafely( messagePath );
                                    continue;
                                }

                                if( messagePath.toString().endsWith( ".lock" ) ) {
                                    var binFile = Paths.get( FilenameUtils.removeExtension( messagePath.toString() ) + ".bin" );
                                    if( !java.nio.file.Files.exists( binFile ) ) {
                                        log.warn( "invalid lock file {}", messagePath );
                                        Files.deleteSafely( messagePath );
                                    }

                                    continue;
                                }

                                String md5HexWithVersion = FilenameUtils.removeExtension( FilenameUtils.getName( messagePath.toString() ) );
                                String md5Hex;
                                short version;
                                var versionStart = md5HexWithVersion.lastIndexOf( '-' );
                                if( versionStart > 0 ) {
                                    version = Short.parseShort( md5HexWithVersion.substring( versionStart + 1 ) );
                                    md5Hex = md5HexWithVersion.substring( 0, versionStart );
                                } else {
                                    version = 1;
                                    md5Hex = md5HexWithVersion;
                                }
                                var md5 = ByteSequence.of( Hex.decodeHex( md5Hex ) );

                                Path lockFile;

                                synchronized( syncDiskLock ) {
                                    if( closed ) return this;

                                    if( ( lockFile = lock( uniqueName, messagePath, storageLockExpiration ) ) != null ) {
                                        log.info( "[{}] reading unsent message {}", uniqueName, messagePath );
                                        try {
                                            var data = Files.read( messagePath, ContentReader.ofBytes() );

                                            log.info( "[{}] client id {} message type {} md5 {}",
                                                uniqueName, msgClientId, MessageProtocol.messageTypeToString( messageType ), md5Hex );

                                            var message = new Message( clientId, messageType, version, md5, data );
                                            messages.add( message );

                                            Files.delete( messagePath );
                                        } catch( Exception e ) {
                                            LogConsolidated.log( log, Level.ERROR, Dates.s( 5 ), "[" + uniqueName + "] " + messagePath + ": " + e.getMessage(), e );
                                        } finally {
                                            Files.delete( lockFile );
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Files.deleteEmptyDirectories( directory, false );

        return this;
    }

    private boolean isValidMessage( Path messagePath ) {
        try {
            if( java.nio.file.Files.isDirectory( messagePath ) ) return false;
            String messageFile = messagePath.toString();
            if( !messageFile.endsWith( ".bin" ) && !messageFile.endsWith( ".lock" ) ) return false;

            var md5HexWithVersion = FilenameUtils.removeExtension( FilenameUtils.getName( messagePath.toString() ) );
            var versionStart = md5HexWithVersion.lastIndexOf( '-' );
            if( versionStart > 0 ) {
                Short.parseShort( md5HexWithVersion.substring( versionStart + 1 ) );
                ByteSequence.of( Hex.decodeHex( md5HexWithVersion.substring( 0, versionStart ) ) );
            } else {
                ByteSequence.of( Hex.decodeHex( md5HexWithVersion ) );
            }

            return true;
        } catch( DecoderException | NumberFormatException e ) {
            return false;
        }
    }

    private boolean isValidMessageType( Path messageTypePath ) {
        try {
            return java.nio.file.Files.isDirectory( messageTypePath ) && ( byte ) Integer.parseInt( FilenameUtils.getName( messageTypePath.toString() ) ) > 0;
        } catch( NumberFormatException e ) {
            return false;
        }
    }

    private boolean isValidClientId( Path clientIdPath ) {
        try {
            return java.nio.file.Files.isDirectory( clientIdPath ) && Long.parseLong( FilenameUtils.getName( clientIdPath.toString() ), 16 ) > 0;
        } catch( NumberFormatException e ) {
            return false;
        }
    }

    public boolean isEmpty() {
        return messages.isEmpty();
    }

    public int getReadyMessages() {
        return messages.getReadyMessages();
    }

    public int getRetryMessages() {
        return messages.getRetryMessages();
    }

    public int getInProgressMessages() {
        return messages.getInProgressMessages();
    }

    public void reset() {
        messages.reset();
    }
}
