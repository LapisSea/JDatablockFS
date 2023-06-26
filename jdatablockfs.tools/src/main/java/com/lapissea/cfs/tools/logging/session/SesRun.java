package com.lapissea.cfs.tools.logging.session;

import com.lapissea.cfs.io.impl.MemoryData;

import java.io.File;
import java.io.IOException;

public class SesRun{
	
	
	public static void main(String[] args) throws IOException{
		try(var service = SessionService.of(new File("sessions.dfs"))){
			try(var ses = service.openSession()){
				var data = MemoryData.builder().withOnWrite(ses).build();

//				var cl=Cluster.init(data);
//				cl.w
			}
		}
	}
	
}
