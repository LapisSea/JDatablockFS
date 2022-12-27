package com.lapissea.cfs.run.sparseimage;

import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.run.Configuration;
import com.lapissea.cfs.tools.logging.LoggedMemoryUtils;
import com.lapissea.util.LogUtil;

import java.io.File;
import java.io.IOException;
import java.util.Random;
import java.util.stream.LongStream;

import static com.lapissea.util.LogUtil.Init.USE_CALL_POS;
import static com.lapissea.util.LogUtil.Init.USE_CALL_THREAD;
import static com.lapissea.util.LogUtil.Init.USE_TABULATED_HEADER;
import static com.lapissea.util.LogUtil.Init.USE_TIME_DELTA;

public class SparseImage{
	
	public static void main(String[] args) throws Exception{
		
		LogUtil.Init.attach(USE_CALL_POS|USE_CALL_THREAD|USE_TIME_DELTA|USE_TABULATED_HEADER);
		LogUtil.println("Start");
		
		var config = new Configuration();
		config.load(new Configuration.Loader.JsonArgs(new File("SparseImage.json"), true));
		config.load(new Configuration.Loader.DashedNameValueArgs(args));
		long tim = System.currentTimeMillis();
		main(config.getView());
		LogUtil.println(System.currentTimeMillis() - tim);
	}
	
	public static void main(Configuration.View args) throws IOException{
		String sessionName = "default";
		var    logger      = LoggedMemoryUtils.createLoggerFromConfig();
		
		try{
			var mem = LoggedMemoryUtils.newLoggedMemory(sessionName, logger);
			logger.ifInited(l -> l.getSession(sessionName).reset());
			
			try{
				LogUtil.println("init");
				var cluster = Cluster.init(mem);
				
				LogUtil.println("run");
				run(cluster, args);
				
			}finally{
				logger.block();
				mem.getHook().writeEvent(mem, LongStream.of());
			}
		}finally{
			logger.get().destroy();
		}
		LogUtil.println("done");
	}
	
	public static void run(Cluster cluster, Configuration.View args) throws IOException{
		
		int radius     = args.getInt("radius", 50);
		int iterations = args.getInt("iterations", 100);
		
		LogUtil.println("data gen");
		var image = cluster.getRootProvider().request("my image", Image.class);
		
		Random r = new Random(1);
		for(int i = 0; i<iterations; i++){
			int x = (int)(Math.pow(r.nextFloat(), 3)*radius);
			int y = (int)(Math.pow(r.nextFloat(), 3)*radius);
			image.set(x, y, r.nextFloat(), r.nextFloat(), 1);
		}
		LogUtil.println("Defrag");
		cluster.defragment();
		
	}
	
}
