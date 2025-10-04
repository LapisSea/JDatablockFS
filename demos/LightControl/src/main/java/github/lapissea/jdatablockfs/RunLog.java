package github.lapissea.jdatablockfs;

import com.google.gson.GsonBuilder;
import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.io.IOHook;
import com.lapissea.dfs.io.IOInterface;
import com.lapissea.dfs.objects.collections.IOList;
import com.lapissea.dfs.tools.logging.DataLogger;
import com.lapissea.dfs.tools.logging.LoggedMemoryUtils;
import com.lapissea.dfs.tools.logging.MemFrame;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.LogUtil;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.stream.LongStream;

public final class RunLog{
	
	public sealed interface LightValue{
		@IOValue
		final class Lux extends IOInstance.Managed<Lux> implements LightValue{
			public final float lux;
			public Lux(float lux){ this.lux = lux; }
		}
		
		@IOValue
		final class ServerError extends IOInstance.Managed<GetError> implements LightValue{
			public final String error;
			public ServerError(String error){ this.error = error; }
		}
		
		@IOValue
		final class GetError extends IOInstance.Managed<GetError> implements LightValue{
			public final String error;
			public GetError(String error){ this.error = error; }
		}
	}
	
	@IOValue
	public static class LightStamp extends IOInstance.Managed<LightStamp>{
		public final Instant    time;
		public final LightValue value;
		
		public LightStamp(Instant time, LightValue value){
			this.time = time;
			this.value = value;
		}
	}
	
	static String command;
	
	public static void main(String[] args) throws IOException, InterruptedException{
		
		Thread.startVirtualThread(() -> {
			var s = new Scanner(System.in);
			while(true){
				command = s.nextLine();
			}
		});
		
		var cluster = Cluster.initOrOpen(IOInterface.build().withFile("./sensorInfo.db").withHook(makeHook()).build());
		
		IOList<LightStamp> stamps = cluster.roots().request("lightLog", IOList.class, LightStamp.class);
		
		var iArgs = Iters.of(args);
		if(iArgs.anyEquals("export")){
			export(stamps);
			return;
		}
		
		var serverURI     = URI.create("http://192.168.0.16:42069");
		var cookieManager = new CookieManager();
		
		if(iArgs.anyMatch(e -> e.startsWith("user="))){
			var cookie = new HttpCookie(
				iArgs.filter(e -> e.startsWith("user=")).getFirst().substring(5),
				iArgs.filter(e -> e.startsWith("login=")).getFirst().substring(6)
			);
			cookie.setDomain("192.168.0.16");
			cookie.setPath("/");
			cookieManager.getCookieStore().add(serverURI, cookie);
		}
		
		try(var client = HttpClient.newBuilder().cookieHandler(cookieManager).build()){
			HttpRequest request = HttpRequest.newBuilder().uri(serverURI).GET().build();
			
			LogUtil.println("Starting logging...");
			Instant last = Instant.now();
			while(true){
				while(last.plusSeconds(1).isAfter(Instant.now())){
					Thread.sleep(10);
				}
				
				if(command != null){
					switch(command.trim()){
						case "export" -> export(stamps);
						case "exit" -> { return; }
					}
					command = null;
				}
				
				LightValue value = null;
				getVal:
				try{
					var response = client.send(request, HttpResponse.BodyHandlers.ofString());
					var body     = response.body();
					if(response.statusCode() != 200){
						value = new LightValue.ServerError(response + " " + body);
						break getVal;
					}
					//noinspection unchecked
					Map<String, Object> data = new GsonBuilder().create().fromJson(body, HashMap.class);
					if(data.containsKey("lux")){
						value = new LightValue.Lux(((Number)data.get("lux")).floatValue());
					}else if(data.containsKey("error")){
						value = new LightValue.ServerError((String)data.get("error"));
						LogUtil.println(value);
					}
				}catch(Exception e){
					value = new LightValue.GetError(e.toString());
					e.printStackTrace();
				}
				var stamp = new LightStamp(last = Instant.now(), value);
				stamps.add(stamp);
				
				if(stamps.size()%10 == 0){
					//TODO: Implement an contiguous dequeue structure
					while(olderThan(stamps.getFirst().time, 40)){
						stamps.remove(0);
					}
					
					//TODO: improve memory usage patterns, implement transparent defragmentation maybe?
					if(cluster.getMemoryManager().getFreeChunks().size()>=100){
						LogUtil.println("Reformating file...");
						var values = stamps.iter().filter(e -> !olderThan(e.time, 40)).toList();
						stamps.clear();
						stamps.addAll(values);
						LogUtil.println("reformatted " + values.size() + " records");
					}
					LogUtil.println("Record count: " + stamps.size() + "," +
					                "\tdbSize: " + cluster.getSource().getIOSize() + "," +
					                "\tfreeChunks: " + cluster.getMemoryManager().getFreeChunks().size());
				}
			}
		}
		
		
	}
	private static IOHook makeHook(){
		var    logger = LoggedMemoryUtils.createLoggerFromConfig().get();
		IOHook hook   = null;
		if(logger != DataLogger.Blank.INSTANCE){
			var ses = logger.getSession("test");
			hook = new IOHook(){
				int id = 0;
				@Override
				public void writeEvent(IOInterface data, LongStream changeIds) throws IOException{
					ses.log(new MemFrame(id++, 0, data.readAll(), changeIds.toArray(), new Throwable()));
				}
			};
		}
		return hook;
	}
	private static void export(IOList<LightStamp> stamps) throws IOException{
		LogUtil.println("start export");
		try(var out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream("graph.csv")))){
			out.write("Time, Lux, Error\n");
			for(LightStamp stamp : stamps){
				var lux = switch(stamp.value){
					case LightValue.Lux v -> v.lux + "";
					default -> "";
				};
				var error = switch(stamp.value){
					case LightValue.GetError v -> v.error;
					case LightValue.ServerError v -> v.error;
					default -> "";
				};
				
				out.write(toExcelDate(stamp.time) + "," + lux + "," + error + "\n");
			}
		}
		LogUtil.println("Ok");
	}
	public static String toExcelDateHuman(Instant instant){
		LocalDateTime ldt = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
		return DateTimeFormatter.ofPattern("HH:mm:ss").format(ldt);
	}
	public static double toExcelDate(Instant instant){
		// Excel epoch: 1899-12-31T00:00:00Z
		Instant excelEpoch = LocalDate.of(1899, 12, 31)
		                              .atStartOfDay(ZoneOffset.UTC)
		                              .toInstant();
		
		long   seconds  = Duration.between(excelEpoch, instant).getSeconds();
		long   days     = seconds/86400;
		double fraction = (seconds%86400)/86400.0;
		
		return days + fraction;
	}
	public static boolean olderThan(Instant instant, int hours){
		return instant.isBefore(Instant.now().minus(Duration.ofHours(hours)));
	}
}
