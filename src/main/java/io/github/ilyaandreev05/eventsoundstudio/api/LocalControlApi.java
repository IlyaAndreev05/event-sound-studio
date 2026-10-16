package io.github.ilyaandreev05.eventsoundstudio.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.ilyaandreev05.eventsoundstudio.audio.EventRouter;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/** Optional loopback-only HTTP control API, disabled by default and protected by a random bearer token. */
public final class LocalControlApi implements AutoCloseable {
    private final EventRouter router;
    private final Consumer<String> listener;
    private final ObjectMapper json = new ObjectMapper();
    private final ExecutorService requests = Executors.newFixedThreadPool(2, r->{Thread t=new Thread(r,"ess-local-api");t.setDaemon(true);return t;});
    private volatile HttpServer server;
    private volatile String token;
    private volatile int port=-1;
    public LocalControlApi(EventRouter router,Consumer<String> listener){this.router=router;this.listener=listener==null?ignored->{}:listener;}
    public synchronized Status enable() throws IOException {
        if(server!=null)return new Status(true,port,token);
        HttpServer next=HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),8);
        token=newToken();next.createContext("/v1/play",this::play);next.createContext("/v1/trigger",this::trigger);next.setExecutor(requests);next.start();
        server=next;port=next.getAddress().getPort();listener.accept("Local API enabled on 127.0.0.1:"+port);return new Status(true,port,token);
    }
    public synchronized void disable(){if(server!=null){server.stop(0);server=null;}port=-1;token=null;listener.accept("Local API disabled");}
    public boolean enabled(){return server!=null;}
    public Status status(){return new Status(enabled(),port,token);}
    private void play(HttpExchange exchange)throws IOException{handle(exchange,"play");}
    private void trigger(HttpExchange exchange)throws IOException{handle(exchange,"trigger");}
    private void handle(HttpExchange exchange,String kind)throws IOException{
        if(!"POST".equals(exchange.getRequestMethod())){respond(exchange,405,"{\"error\":\"POST required\"}");return;}
        String auth=exchange.getRequestHeaders().getFirst("Authorization");String expected="Bearer "+token;
        if(token==null||auth==null||!MessageDigest.isEqual(auth.getBytes(StandardCharsets.UTF_8),expected.getBytes(StandardCharsets.UTF_8))){respond(exchange,401,"{\"error\":\"unauthorized\"}");return;}
        byte[] body=exchange.getRequestBody().readNBytes(2049);if(body.length>2048){respond(exchange,413,"{\"error\":\"request too large\"}");return;}
        try{
            Map<String,Object> request=json.readValue(body,new TypeReference<>(){});String id=String.valueOf(request.getOrDefault(kind.equals("play")?"bindingId":"eventId",""));
            if(id.isBlank()){respond(exchange,400,"{\"error\":\"missing id\"}");return;}
            boolean ok=kind.equals("play")?router.playBinding(id):router.trigger(id);
            if(!ok){respond(exchange,404,"{\"error\":\"no enabled binding or loaded sound\"}");return;}
            respond(exchange,202,"{\"status\":\"accepted\"}");
        }catch(Exception e){respond(exchange,400,"{\"error\":\"invalid JSON\"}");}
    }
    private static void respond(HttpExchange exchange,int code,String body)throws IOException{
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.sendResponseHeaders(code,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}finally{exchange.close();}
    }
    private static String newToken(){byte[]bytes=new byte[32];new SecureRandom().nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    @Override public void close(){disable();requests.shutdownNow();}
    public record Status(boolean enabled,int port,String token){}
}
