package github.lapissea.jdatablockfs;

import com.google.gson.GsonBuilder;
import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.io.IOHook;
import com.lapissea.dfs.io.IOInterface;
import com.lapissea.dfs.io.impl.MemoryData;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.objects.collections.IOList;
import com.lapissea.dfs.tools.logging.DataLogger;
import com.lapissea.dfs.tools.logging.LoggedMemoryUtils;
import com.lapissea.dfs.tools.logging.MemFrame;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.LogUtil;
import com.lapissea.util.UtilL;

import java.io.BufferedWriter;
import java.io.File;
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
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.StringJoiner;
import java.util.stream.LongStream;

import static com.lapissea.dfs.query.Query.Test.fieldGr;

public final class RunLog{
	
	public sealed interface LightValue{
		@IOValue
		final class Lux extends IOInstance.Managed<Lux> implements LightValue{
			public final float lux;
			public Lux(float lux){
				this.lux = lux;
				if(lux<0){
					throw new IllegalArgumentException("Lux can not be negative");
				}
			}
			public float getDisplayLux(){
				return Math.min(lux + 190, (float)mapping.luxMax);
			}
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
		
		@IOValue
		public Instant time(){
			return time;
		}
	}
	
	private static final String TWINKLE_TRAY = "C:\\Program Files\\WindowsApps\\38002AlexanderFrangos.TwinkleTray_2025.10823.10157.0_x64__m7qx9dzpwqaze\\app\\Twinkle Tray.exe";
	
	static String command;
	static float  monitorLux;
	static float  monitorLuxTarget;
	
	static final Duration poolFrequency = Duration.ofMillis(500);
	static final Duration maxRecordAge  = Duration.ofHours(24*2);
	
	public static void main(String[] args) throws IOException, InterruptedException{
		
		Thread.startVirtualThread(() -> {
			var s = new Scanner(System.in);
			while(true){
				command = s.nextLine();
			}
		});
		
		var dbFile  = IOInterface.build().withFile("./sensorInfo.db").withHook(makeHook()).build();
		var cluster = Cluster.initOrOpen(dbFile);
		
		IOList<LightStamp> stamps = cluster.roots().request("lightLog", IOList.class, LightStamp.class);
		
		if(!stamps.isEmpty()){
			var iter = stamps.listIterator(stamps.size());
			while(iter.hasPrevious()){
				var val = iter.ioPrevious().value;
				if(val instanceof LightValue.Lux v){
					monitorLuxTarget = v.getDisplayLux();
					monitorLux = monitorLuxTarget - 3;
					break;
				}
			}
		}
		
		var iArgs = Iters.of(args);
		if(iArgs.anyEquals("export")){
			export(stamps);
			return;
		}
		if(iArgs.noneEquals("singleReport")){
			startMonitorControl();
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
				while(last.plus(poolFrequency).isAfter(Instant.now())){
					Thread.sleep(10);
				}
				boolean rebuild = false;
				if(command != null){
					switch(command.trim()){
						case "export" -> {
							export(stamps);
							System.gc();
						}
						case "exit" -> { return; }
						case "rebuild" -> rebuild = true;
						default -> LogUtil.println("Warning: Unknown command: " + command.trim());
					}
					command = null;
				}
				
				LightValue value = requestValue(client, request);
				
				if(iArgs.anyEquals("singleReport")){
					LogUtil.println(value);
					return;
				}
				
				if(value instanceof LightValue.Lux val){
					monitorLuxTarget = val.getDisplayLux();
				}
				
				var stamp = new LightStamp(last = Instant.now(), value);
				stamps.add(stamp);
				
				if(stamps.size()%10 == 0 || rebuild){
					//TODO: Implement an contiguous dequeue structure
					var cutoff = Instant.now().minus(maxRecordAge);
					if(!stamps.isEmpty() && stamps.getFirst().time.isBefore(cutoff.minus(Duration.ofHours(12)))){
						LogUtil.println("Removing old data...");
						long count = 0;
						try(var ignore = dbFile.openIOTransaction()){
							while(!stamps.isEmpty() && stamps.getFirst().time.isBefore(cutoff)){
								stamps.remove(0);
								count++;
							}
						}
						LogUtil.println("Removed " + count + " records");
					}
					
					//TODO: improve memory usage patterns, implement transparent defragmentation maybe?
					if(cluster.getMemoryManager().getFreeChunks().size()>=256 || rebuild){
						LogUtil.println("Reformating file...");
						var tmpFile = MemoryData.empty();
						
						try(var cleanData = stamps.query().where(fieldGr(LightStamp::time, cutoff)).open()){
							Cluster            newCluster = Cluster.init(tmpFile);
							IOList<LightStamp> newStamps  = newCluster.roots().request("lightLog", IOList.class, LightStamp.class);
							
							List<LightStamp> chunk;
							while(!(chunk = cleanData.nextFullEntries(4096)).isEmpty()){
								newStamps.addAll(chunk);
								LogUtil.println("Moved chunk: " + chunk.size());
							}
						}
						
						LogUtil.println("Writing...");
						
						File backups = new File("backups");
						backups.mkdirs();
						File newBackup = new File(backups, "sensorInfo_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".db");
						try(var io = new FileOutputStream(newBackup)){
							dbFile.transferTo(io);
						}
						
						dbFile.set(tmpFile);
						cluster = new Cluster(dbFile);
						stamps = cluster.roots().request("lightLog", IOList.class, LightStamp.class);
						System.gc();
						LogUtil.println("reformatted " + stamps.size() + " records");
					}
					
					log(cluster, stamps);
				}
			}
		}
	}
	
	private static void log(Cluster cluster, IOList<LightStamp> stamps) throws IOException{
		long sum = 0;
		for(ChunkPointer freeChunk : cluster.getMemoryManager().getFreeChunks()){
			sum += freeChunk.dereference(cluster).getCapacity();
		}
		
		LogUtil.println("Record count: " + stamps.size() + "," +
		                "\tdbSize: " + cluster.getSource().getIOSize() + "," +
		                "\tfreeChunks: " + cluster.getMemoryManager().getFreeChunks().size() + "," +
		                "\tfreeSpace: " + sum
		);
	}
	
	private static LightValue requestValue(HttpClient client, HttpRequest request){
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
		return value;
	}
	
	static final BrightnessMapping mapping = new BrightnessMapping(
		new double[]{0, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70, 75, 78, 80, 81, 82, 83, 85, 86, 89, 90, 91, 92, 95, 100},
		new double[]{190, 199, 207, 215, 221, 234, 242, 250, 266, 272, 278, 291, 298, 305, 320, 325, 331, 339, 383, 435, 480, 572, 620, 746, 794, 804, 819, 863, 932}
	);
	
	private static void startMonitorControl(){
		Thread.startVirtualThread(() -> {
			while(true){
				UtilL.sleep(250);
				runMonitorcontrol();
			}
		});
	}
	private static void runMonitorcontrol(){
		boolean needsUpdate;
		if(monitorLuxTarget<=1 + mapping.luxMin || monitorLuxTarget>=mapping.luxMax - 1){
			needsUpdate = monitorLux != monitorLuxTarget;
		}else{
			needsUpdate = Math.abs(monitorLux - monitorLuxTarget)>2;
		}
		if(!needsUpdate){
			return;
		}
		
		if(Math.abs(monitorLux - monitorLuxTarget)<1){
			monitorLux = monitorLuxTarget;
		}else{
			if(monitorLux>monitorLuxTarget){
				monitorLux -= 1;
			}else{
				monitorLux += 1;
			}
			monitorLux = (monitorLux*10.0F + monitorLuxTarget)/11;
		}
		
		var percent = mapping.luxToPercent(monitorLux);
		setMonitorPercent((int)Math.round(percent));
	}
	
	private static int lastPercent = -1;
	private static void setMonitorPercent(int percent){
		if(lastPercent == percent){
			return;
		}
		lastPercent = percent;
		
		LogUtil.println("Setting new monitor percent: " + percent);
		ProcessBuilder pb = new ProcessBuilder(TWINKLE_TRAY, "--All", "--Set=" + percent);
		pb.redirectErrorStream(true);
		try{
			pb.start().waitFor();
		}catch(Throwable e){
			e.printStackTrace();
			System.exit(1);
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
			out.write("Time,Lux,Display Lux,Error\n");
			for(LightStamp stamp : stamps){
				var row = new StringJoiner(",");
				row.add(toExcelDate(stamp.time) + "");
				row.add(switch(stamp.value){
					case LightValue.Lux v -> v.lux + "";
					default -> "";
				});
				row.add(switch(stamp.value){
					case LightValue.Lux v -> mapping.luxToPercent(v.getDisplayLux()) + "";
					default -> "";
				});
				row.add(switch(stamp.value){
					case LightValue.GetError v -> v.error;
					case LightValue.ServerError v -> v.error;
					default -> "";
				});
				
				out.write(row + "\n");
			}
		}
		LogUtil.println("Ok");
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
