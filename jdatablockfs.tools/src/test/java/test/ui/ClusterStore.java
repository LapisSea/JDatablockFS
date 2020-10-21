package test.ui;

import com.google.gson.GsonBuilder;
import com.lapissea.cfs.cluster.Cluster;
import com.lapissea.cfs.cluster.extensions.BlockMapCluster;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.io.struct.IOInstance;
import com.lapissea.cfs.tools.DataLogger;
import com.lapissea.cfs.tools.DisplayServer;
import com.lapissea.cfs.tools.MemFrame;
import com.lapissea.util.*;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Objects;

public class ClusterStore{
	
	public static <K extends IOInstance> BlockMapCluster<K> start(Class<K> type) throws IOException{
		
		Map<String, String> config=new HashMap<>();
		try(var r=new FileReader(new File("config.json"))){
			new GsonBuilder().create().<Map<String, Object>>fromJson(r, HashMap.class).forEach((k, v)->config.put(k, TextUtil.toString(v)));
		}catch(Exception ignored){ }
		
		System.setProperty("com.lapissea.cfs.tools.DisplayServer.THREADED_OUTPUT", config.getOrDefault("threadedOutput", ""));
		LogUtil.Init.attach(Boolean.parseBoolean(config.getOrDefault("fancyPrint", "true"))?LogUtil.Init.USE_CALL_POS|LogUtil.Init.USE_TABULATED_HEADER:0);
		
		LateInit<DataLogger> display=new LateInit<>(()->new DisplayServer(Objects.toString(config.get("serverDisplayJar"))));
		
		var mem=new MemoryData();
		
		BlockMapCluster<K> cluster=new BlockMapCluster<>(Cluster.build(b->b.withIO(mem)), type);
		
		var preBuf=new LinkedList<MemFrame>();
		Runnable flush=()->{
			display.ifInited(d->{
				while(!preBuf.isEmpty()){
					d.log(preBuf.remove(0));
				}
			});
		};
		mem.onWrite=ids->{
			preBuf.add(new MemFrame(mem.readAll(), ids, new Throwable()));
			flush.run();
		};
		mem.onWrite.accept(ZeroArrays.ZERO_LONG);
		
		new Thread(()->{
			UtilL.sleepUntil(display::isInited);
			flush.run();
		}).start();
		return cluster;
	}
}
