package nl.altindag.client;

import akka.actor.ActorSystem;
import akka.http.javadsl.ConnectionContext;
import akka.http.javadsl.HttpsConnectionContext;
import com.github.mizosoft.methanol.Methanol;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.gson.GsonBuilder;
import com.sun.jersey.api.client.config.DefaultClientConfig;
import com.sun.jersey.client.urlconnection.HTTPSProperties;
import com.twitter.finagle.Http;
import com.twitter.finagle.Service;
import com.twitter.finagle.http.Request;
import com.twitter.finagle.http.Response;
import com.typesafe.config.ConfigFactory;
import feign.Feign;
import io.netty.handler.ssl.SslContext;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import kong.unirest.Unirest;
import nl.altindag.ssl.SSLFactory;
import nl.altindag.ssl.util.Apache4SslUtils;
import nl.altindag.ssl.util.Apache5SslUtils;
import nl.altindag.ssl.util.JettySslUtils;
import nl.altindag.ssl.util.NettySslUtils;
import okhttp3.OkHttpClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.hc.client5.http.nio.AsyncClientConnectionManager;
import org.apache.http.conn.socket.LayeredConnectionSocketFactory;
import org.apache.http.impl.client.HttpClients;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.DefaultAsyncHttpClientConfig;
import org.asynchttpclient.Dsl;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.reactive.JettyClientHttpConnector;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

import javax.net.ssl.SSLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;

import static java.util.Objects.nonNull;
import static nl.altindag.client.Constants.SERVER_URL;

@Component
public class ClientConfig {

    @Bean
    @Scope("prototype")
    public org.apache.http.impl.client.CloseableHttpClient apacheHttpClient(@Autowired(required = false) SSLFactory sslFactory) {
        if (nonNull(sslFactory)) {
            LayeredConnectionSocketFactory socketFactory = Apache4SslUtils.toSocketFactory(sslFactory);
            return HttpClients.custom()
                    .setSSLSocketFactory(socketFactory)
                    .build();
        } else {
            return HttpClients.createDefault();
        }
    }

    @Bean
    public org.apache.http.impl.nio.client.CloseableHttpAsyncClient apacheHttpAsyncClient(@Autowired(required = false) SSLFactory sslFactory) {
        org.apache.http.impl.nio.client.CloseableHttpAsyncClient client;
        if (nonNull(sslFactory)) {
            client = org.apache.http.impl.nio.client.HttpAsyncClients.custom()
                    .setSSLContext(sslFactory.getSslContext())
                    .setSSLHostnameVerifier(sslFactory.getHostnameVerifier())
                    .build();
        } else {
            client = org.apache.http.impl.nio.client.HttpAsyncClients.createDefault();
        }
        client.start();
        return client;
    }

    @Bean
    public org.apache.hc.client5.http.impl.classic.CloseableHttpClient apache5HttpClient(@Autowired(required = false) SSLFactory sslFactory) {
        if (nonNull(sslFactory)) {
            HttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                    .setSSLSocketFactory(Apache5SslUtils.toSocketFactory(sslFactory))
                    .build();

            return org.apache.hc.client5.http.impl.classic.HttpClients.custom()
                    .setConnectionManager(connectionManager)
                    .build();
        } else {
            return org.apache.hc.client5.http.impl.classic.HttpClients.createDefault();
        }
    }

    @Bean
    public org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient apache5HttpAsyncClient(@Autowired(required = false) SSLFactory sslFactory) {
        org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient client;
        if (nonNull(sslFactory)) {
            AsyncClientConnectionManager connectionManager = PoolingAsyncClientConnectionManagerBuilder.create()
                    .setTlsStrategy(Apache5SslUtils.toTlsStrategy(sslFactory))
                    .build();

            client = org.apache.hc.client5.http.impl.async.HttpAsyncClients.custom()
                    .setConnectionManager(connectionManager)
                    .build();
        } else {
            client = HttpAsyncClients.createDefault();
        }

        client.start();
        return client;
    }

    @Bean
    public HttpClient jdkHttpClient(@Autowired(required = false) SSLFactory sslFactory) {
        if (nonNull(sslFactory)) {
            return HttpClient.newBuilder()
                    .sslParameters(sslFactory.getSslParameters())
                    .sslContext(sslFactory.getSslContext())
                    .build();
        } else {
            return HttpClient.newHttpClient();
        }
    }

    @Bean
    public RestTemplate restTemplate(org.apache.http.impl.client.CloseableHttpClient httpClient) {
        HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory();
        requestFactory.setHttpClient(httpClient);
        return new RestTemplate(requestFactory);
    }

    @Bean
    @Scope("prototype")
    public OkHttpClient okHttpClient(@Autowired(required = false) SSLFactory sslFactory) {
        if (nonNull(sslFactory)) {
            return new OkHttpClient.Builder()
                    .sslSocketFactory(sslFactory.getSslSocketFactory(), sslFactory.getTrustManager().orElseThrow())
                    .hostnameVerifier(sslFactory.getHostnameVerifier())
                    .build();
        } else {
            return new OkHttpClient();
        }
    }

    @Bean
    @Scope("prototype")
    public reactor.netty.http.client.HttpClient nettyHttpClient(@Autowired(required = false) SSLFactory sslFactory) throws SSLException {
        reactor.netty.http.client.HttpClient httpClient = reactor.netty.http.client.HttpClient.create();
        if (nonNull(sslFactory)) {
            SslContext sslContext = NettySslUtils.forClient(sslFactory).build();
            httpClient = httpClient.secure(sslSpec -> sslSpec.sslContext(sslContext));
        }
        return httpClient;
    }

    @Bean
    @Scope("prototype")
    public org.eclipse.jetty.client.HttpClient jettyHttpClient(@Autowired(required = false) SSLFactory sslFactory) {
        if (nonNull(sslFactory)) {
            SslContextFactory.Client sslContextFactory = JettySslUtils.forClient(sslFactory);
            return new org.eclipse.jetty.client.HttpClient(sslContextFactory);
        } else {
            return new org.eclipse.jetty.client.HttpClient();
        }
    }

    @Bean
    public WebClient webClientWithNetty(reactor.netty.http.client.HttpClient httpClient) {
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    @Bean
    public WebClient webClientWithJetty(org.eclipse.jetty.client.HttpClient httpClient) {
        return WebClient.builder()
                .clientConnector(new JettyClientHttpConnector(httpClient))
                .build();
    }

    @Bean
    public Client jerseyClient(@Autowired(required = false) SSLFactory sslFactory) {
        if (nonNull(sslFactory)) {
            return ClientBuilder.newBuilder()
                    .sslContext(sslFactory.getSslContext())
                    .hostnameVerifier(sslFactory.getHostnameVerifier())
                    .build();
        } else {
            return ClientBuilder.newClient();
        }
    }

    @Bean
    public com.sun.jersey.api.client.Client oldJerseyClient(@Autowired(required = false) SSLFactory sslFactory) {
        if (nonNull(sslFactory)) {
            DefaultClientConfig clientConfig = new DefaultClientConfig();
            clientConfig.getProperties().put(HTTPSProperties.PROPERTY_HTTPS_PROPERTIES, new HTTPSProperties(sslFactory.getHostnameVerifier(), sslFactory.getSslContext()));
            return com.sun.jersey.api.client.Client.create(clientConfig);
        } else {
            return com.sun.jersey.api.client.Client.create();
        }
    }

    @Bean
    public HttpTransport googleHttpClient(@Autowired(required = false) SSLFactory sslFactory) {
        if (nonNull(sslFactory)) {
            return new NetHttpTransport.Builder()
                    .setSslSocketFactory(sslFactory.getSslSocketFactory())
                    .setHostnameVerifier(sslFactory.getHostnameVerifier())
                    .build();
        } else {
            return new NetHttpTransport();
        }
    }

    @Autowired
    public void unirest(@Autowired(required = false) SSLFactory sslFactory) {
        if (nonNull(sslFactory)) {
            Unirest.primaryInstance()
                   .config()
                   .sslContext(sslFactory.getSslContext())
                   .protocols(sslFactory.getSslParameters().getProtocols())
                   .ciphers(sslFactory.getSslParameters().getCipherSuites())
                   .hostnameVerifier(sslFactory.getHostnameVerifier());
        }
    }

    @Bean
    public Retrofit retrofit(OkHttpClient okHttpClient) {
        return new Retrofit.Builder()
                .client(okHttpClient)
                .baseUrl(SERVER_URL)
                .addConverterFactory(GsonConverterFactory.create(new GsonBuilder().setLenient().create()))
                .build();
    }

    @Bean
    public Service<Request, Response> finagle(@Autowired(required = false) SSLFactory sslFactory) throws URISyntaxException {
        URI uri = new URI(SERVER_URL);
        Http.Client client = Http.client();
        if (nonNull(sslFactory)) {
            client = client
                    .withTransport()
                    .tls(sslFactory.getSslContext());
        }
        return client.newService(uri.getHost() + ":" + uri.getPort());
    }

    @Bean
    public ActorSystem actorSystem() {
        return ActorSystem.create(
                ClientConfig.class.getSimpleName(),
                ConfigFactory.defaultApplication(ClientConfig.class.getClassLoader())
        );
    }

    @Bean
    public akka.http.javadsl.Http akkaHttpClient(@Autowired(required = false) SSLFactory sslFactory,
                                                 ActorSystem actorSystem) {
        akka.http.javadsl.Http http = akka.http.javadsl.Http.get(actorSystem);
        if (nonNull(sslFactory)) {
            HttpsConnectionContext httpsContext = ConnectionContext.httpsClient(sslFactory.getSslContext());
            http.setDefaultClientHttpsContext(httpsContext);
        }
        return http;
    }

    @Bean
    public AsyncHttpClient asyncHttpClient(@Autowired(required = false) SSLFactory sslFactory) throws SSLException {
        if (nonNull(sslFactory)) {
            SslContext sslContext = NettySslUtils.forClient(sslFactory).build();

            DefaultAsyncHttpClientConfig.Builder clientConfigBuilder = dispatch.Http.defaultClientBuilder()
                    .setSslContext(sslContext);

            return Dsl.asyncHttpClient(clientConfigBuilder);
        } else {
            return Dsl.asyncHttpClient();
        }
    }

    @Bean
    public Feign.Builder feign(@Autowired(required = false) SSLFactory sslFactory) {
        if (nonNull(sslFactory)) {
            return Feign.builder()
                    .client(new feign.Client.Default(sslFactory.getSslSocketFactory(), sslFactory.getHostnameVerifier()));
        } else {
            return Feign.builder();
        }
    }

    @Bean
    public Methanol methanol(@Autowired(required = false) SSLFactory sslFactory) {
        if (nonNull(sslFactory)) {
            return Methanol.newBuilder()
                    .sslContext(sslFactory.getSslContext())
                    .sslParameters(sslFactory.getSslParameters())
                    .build();
        } else {
            return Methanol.create();
        }
    }

}
