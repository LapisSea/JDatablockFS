package com.lapissea.dfs.inspect;

import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.io.impl.MemoryData;
import com.lapissea.dfs.objects.collections.IOMap;
import com.lapissea.dfs.utils.RawRandom;
import com.lapissea.util.LogUtil;

public final class LogServerDummyData{
	
	public static void main(String[] args){
		mapAdd();
	}
	
	static void empty(){
		try{
			var mem = Cluster.emptyMem();
			mem.roots().provide(69, "This is a test!!! :D");
			
			try(var remote = new DBLogConnection.OfRemote()){
				try(var ses = remote.openSession("test")){
					var dst = MemoryData.builder().withOnWrite(ses.getIOHook()).build();
					mem.getSource().transferTo(dst, true);
				}
				LogUtil.println("======= Sent frame =======");
				
				remote.openSession("empty").close();
				LogUtil.println("======= Sent empty frame =======");
			}
		}catch(Throwable e){
			new RuntimeException("Failed to send dummy data", e).printStackTrace();
			System.exit(1);
		}
	}
	static void mapAdd(){
		try(var remote = new DBLogConnection.OfRemote();
		    var ses = remote.openSession("bigMap")){
			var dst = MemoryData.builder().withOnWrite(ses.getIOHook()).build();
			
			var                    cluster = Cluster.init(dst);
			IOMap<Integer, String> map     = cluster.roots().request(0, IOMap.class, Integer.class, String.class);
			for(int i = 0; i<20; i++){
				var val = ("int(" + map.size() + ")").repeat(new RawRandom(dst.getIOSize() + map.size()).nextInt(20));
				map.put((int)map.size(), val);
			}
			LogUtil.println("======= Sent frame =======");
		}catch(Throwable e){
			new RuntimeException("Failed to send dummy data", e).printStackTrace();
			System.exit(1);
		}
	}
}
