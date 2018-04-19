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

package oap.application;

import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

/**
 * Created by igor.petrenko on 08.12.2017.
 */
@Service
@Slf4j
public class OapService {
    private Kernel kernel;

    @Value( "${config}" )
    private String config;

    @Value( "${config-directory:#{null}}" )
    private String confd;

    @Value( "${hosts:#{null}}" )
    private String hosts;

    @Autowired
    private ApplicationContext applicationContext;

    @PostConstruct
    public void start() {
        try {
            log.info( "config = {}, config-directory = {}", config, confd );

            kernel = new Kernel( Module.CONFIGURATION.urlsFromClassPath() );
            kernel.start( config, confd, hosts );

            val factory = ( ConfigurableListableBeanFactory ) applicationContext.getAutowireCapableBeanFactory();

            for( val entry : Application.kernel( Kernel.DEFAULT ) ) {
                log.trace( "oap bean {}...", entry.getKey() );
                factory.registerSingleton( entry.getKey(), entry.getValue() );
            }

            log.debug( "started" );
        } catch( Exception e ) {
            log.error( e.getMessage(), e );
            throw e;
        }
    }

    @PreDestroy
    public void stop() {
        try {
            if( kernel != null ) kernel.stop();
            log.debug( "stopped" );
        } catch( Exception e ) {
            log.error( e.getMessage(), e );
        }
    }
}
