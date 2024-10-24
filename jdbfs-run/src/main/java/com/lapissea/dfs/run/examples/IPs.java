package com.lapissea.dfs.run.examples;

import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.io.IOInterface;
import com.lapissea.dfs.io.impl.MemoryData;
import com.lapissea.dfs.objects.collections.IOList;
import com.lapissea.dfs.tools.logging.LoggedMemoryUtils;
import com.lapissea.dfs.type.IOInstance;

import java.io.IOException;
import java.util.Objects;

public final class IPs{
	
	@IOInstance.Order({"latitude", "longitude", "v6"})
	@IOInstance.StrFormat.Custom("{@v6 at @latitude / @longitude}")
	public interface IP extends IOInstance.Def<IP>{
		
		static IP of(double latitude, double longitude, String v6){
			return IOInstance.Def.of(IP.class, latitude, longitude, v6);
		}
		
		double latitude();
		double longitude();
		String v6();
	}
	
	public static void main(String[] args) throws IOException{
		// set this to true if you have a display server running and this program has a config.json to get a real time display of what is happening
		boolean useDisplayServer = false;
		if(useDisplayServer){
			LoggedMemoryUtils.simpleLoggedMemorySession(IPs::run);
		}else{
			//No need to hassle with a real file, just make an empty in ram IOInterface
			run(MemoryData.empty());
		}
	}
	
	public static void run(IOInterface memory) throws IOException{
		createData(memory);
		printData(memory);
	}
	
	public static void createData(IOInterface memory) throws IOException{
		//Memory needs to be initialized with an empty database.
		//Cluster.init acts as a clean slate for the memory field. Any previously contained data will be deleted
		var cluster = Cluster.init(memory);
		
		//Ask root provider for a list of IPs with the id of "my ips"
		IOList<IP> ips = cluster.roots().request("my ips", IOList.class, IP.class);
		
		//Nice thing to do, reduces the possibility of fragmentation. This is only useful when adding element by element. addAll does not benefit from this
		ips.requestRelativeCapacity(2);
		
		//Adding sample data to database:
		ips.add(IP.of(0.2213415, 0.71346, "2001:0db8:85a3:0000:0000:8a2e:0370:7334"));
		ips.add(IP.of(0.6234, 0.51341123, "2001:0db8:0:1:1:1:1:1"));
	}
	
	
	private static void printData(IOInterface memory) throws IOException{
//		System.out.println(memory.hexdump());
//		System.out.println();
		System.out.println("Data in memory:");
		
		for(var e : new Cluster(memory).roots().listAll()){
			System.out.println(e.getKey() + ": " + switch(e.getValue()){
				//Just a fancy toString that every IOInstance has that allows for printing the data in a more appealing way
				case IOInstance<?> inst -> inst.toString(false, "{\n\t", "\n}", ": ", ",\n\t");
				default -> Objects.toString(e.getValue());
			});
		}
	}
}
