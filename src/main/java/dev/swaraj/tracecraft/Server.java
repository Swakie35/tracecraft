package dev.swaraj.tracecraft;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

public final class Server implements AutoCloseable {
    private final HttpServer server;
    private final TelemetryPipeline pipe;
    public Server(int port,int capacity,int window) throws IOException {
        pipe=new TelemetryPipeline(capacity,window);
        server=HttpServer.create(new InetSocketAddress(System.getenv().getOrDefault("BIND_HOST", "127.0.0.1"),port),0);
        server.createContext("/",this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }
    public int port(){return server.getAddress().getPort();}
    public void start(){server.start();}
    @Override public void close(){server.stop(0);pipe.close();}

    private void handle(HttpExchange e) throws IOException {
        try {
            String path=e.getRequestURI().getPath();String method=e.getRequestMethod();
            if(path.equals("/health") && method.equals("GET")){reply(e,200,"{\"status\":\"ok\"}");return;}
            if(path.equals("/ingest") && method.equals("POST")){
                Map<String,String> query=params(e.getRequestURI().getRawQuery());
                String service=query.get("service");
                long latency=Long.parseLong(query.getOrDefault("latencyMs","-1"));
                boolean success=switch(query.getOrDefault("success","true")){
                    case "true" -> true;case "false" -> false;default -> throw new IllegalArgumentException("success must be true/false");};
                boolean accepted=pipe.submit(new TelemetryPipeline.Event(service,latency,success));
                reply(e,accepted?202:429,accepted?"{\"queued\":true}":"{\"queued\":false,\"error\":\"capacity exceeded\"}");return;
            }
            if(path.equals("/metrics") && method.equals("GET")){reply(e,200,metrics(pipe.snapshot()));return;}
            reply(e,404,"{\"error\":\"Unknown endpoint\"}");
        } catch(IllegalArgumentException ex){reply(e,400,"{\"error\":\"Invalid parameter\"}");}
          finally {e.close();}
    }
    private static Map<String,String> params(String text) {
        Map<String,String> map=new HashMap<>();if(text==null)return map;
        for(String part:text.split("&")){
            String[] p=part.split("=",2);
            map.put(URLDecoder.decode(p[0],StandardCharsets.UTF_8),URLDecoder.decode(p.length>1?p[1]:"",StandardCharsets.UTF_8));
        }
        return map;
    }
    private static String metrics(TelemetryPipeline.Snapshot x) {
        StringBuilder b=new StringBuilder("{\"accepted\":"+x.accepted()+",\"dropped\":"+x.dropped()+",\"processed\":"+x.processed()+",\"pending\":"+x.pending()+",\"services\":{");
        boolean first=true;
        for(var item:x.services().entrySet()){
            if(!first)b.append(',');first=false;
            var s=item.getValue();b.append('"').append(item.getKey()).append("\":{\"total\":").append(s.total())
               .append(",\"failures\":").append(s.failures()).append(",\"p50Millis\":").append(s.p50Millis())
               .append(",\"p95Millis\":").append(s.p95Millis()).append(",\"p99Millis\":").append(s.p99Millis())
               .append(",\"sampleCount\":").append(s.sampleCount()).append('}');
        }
        return b.append("}}").toString();
    }
    private static void reply(HttpExchange e,int code,String body) throws IOException {
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
        e.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
        e.sendResponseHeaders(code,bytes.length);
        try(var out=e.getResponseBody()){out.write(bytes);}
    }
    public static void main(String[] args) throws IOException {
        int port=args.length>0?Integer.parseInt(args[0]):8081;
        Server server=new Server(port,2000,1000);server.start();
        System.out.println("Tracecraft metrics on http://127.0.0.1:"+server.port()+"/metrics");
    }
}
