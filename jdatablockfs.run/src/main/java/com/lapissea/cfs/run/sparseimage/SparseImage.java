package com.lapissea.cfs.run.sparseimage;

import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.run.Configuration;
import com.lapissea.cfs.tools.logging.DataLogger;
import com.lapissea.cfs.tools.logging.LoggedMemoryUtils;
import com.lapissea.util.LateInit;
import com.lapissea.util.LogUtil;
import com.lapissea.util.TextUtil;

import java.io.File;
import java.io.IOException;
import java.util.Random;
import java.util.stream.LongStream;

import static com.lapissea.util.LogUtil.Init.USE_CALL_POS;
import static com.lapissea.util.LogUtil.Init.USE_TABULATED_HEADER;

public class SparseImage{
	
	public static void main(String[] args) throws IOException{
		var config=new Configuration();
		config.load(new Configuration.Loader.JsonArgs(new File("SparseImage.json"), true));
		config.load(new Configuration.Loader.DashedNameValueArgs(args));
		main(config.getView());
	}
	
	public static void main(Configuration.View args) throws IOException{
		
		var config  =LoggedMemoryUtils.readConfig();
		var logFlags=0;
		if(Boolean.parseBoolean(config.getOrDefault("fancyPrint", "false").toString())){
			logFlags=USE_CALL_POS|USE_TABULATED_HEADER;
		}
		LogUtil.Init.attach(logFlags);
		
		LogUtil.println("config:", TextUtil.toNamedPrettyJson(config));
		
		String               sessionName="default";
		LateInit<DataLogger> logger     =LoggedMemoryUtils.createLoggerFromConfig();
		
		try{
			var mem=LoggedMemoryUtils.newLoggedMemory(sessionName, logger);
			logger.ifInited(l->l.getSession(sessionName).reset());
			
			try{
				Cluster.init(mem);
				Cluster cluster=new Cluster(mem);
				
				
				run(cluster, args);
				
			}finally{
				logger.block();
				mem.onWrite.log(mem, LongStream.of());
			}
		}finally{
			logger.get().destroy();
		}
	}
	
	public static void run(Cluster cluster, Configuration.View args) throws IOException{
		
		int radius    =args.getInt("radius", 10);
		int iterations=args.getInt("iterations", 100);
		
		var image=cluster.getRootProvider().request(Image.class, "my image");
		
		Random r=new Random(1);
		for(int i=0;i<iterations;i++){
			int x=(int)(Math.pow(r.nextFloat(), 3)*radius);
			int y=(int)(Math.pow(r.nextFloat(), 3)*radius);
			image.set(x, y, r.nextFloat(), r.nextFloat(), 1);
		}
		
		cluster.defragment();
		
	}
	
}
