package dev.swaraj.tracecraft;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class SmokeTest {
    public static void main(String[] args) throws Exception {
        try(var pipeline=new TelemetryPipeline(200,8)) {
            for(int i=1;i<=50;i++)pipeline.submit(new TelemetryPipeline.Event("search-api",i,i%10!=0));
            long deadline=System.nanoTime()+2_000_000_000L;
            while(pipeline.snapshot().processed()<50 && System.nanoTime()<deadline)Thread.sleep(5);
            var snapshot=pipeline.snapshot();
            assert snapshot.processed()==50:snapshot;
            assert snapshot.services().get("search-api").sampleCount()==8;
            assert snapshot.services().get("search-api").failures()==5;
            assert snapshot.services().get("search-api").p95Millis()==50;
        }
        try(var server=new Server(0,100,10);var http=HttpClient.newHttpClient()) {
            server.start();String uri="http://127.0.0.1:"+server.port();
            var req=HttpRequest.newBuilder(URI.create(uri+"/ingest?service=billing&latencyMs=30&success=false"))
                .POST(HttpRequest.BodyPublishers.noBody()).build();
            assert http.send(req,HttpResponse.BodyHandlers.ofString()).statusCode()==202;
            var r=http.send(HttpRequest.newBuilder(URI.create(uri+"/metrics")).build(),HttpResponse.BodyHandlers.ofString());
            assert r.statusCode()==200;
            assert r.body().contains("\"services\"");
        }
        try{new TelemetryPipeline.Event("bad service !",5,true);throw new AssertionError();}
        catch(IllegalArgumentException expected){}
        System.out.println("Tracecraft pipeline and HTTP tests passed");
    }
}
