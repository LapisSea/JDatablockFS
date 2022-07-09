package com.lapissea.cfs.run.examples;

import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.io.IOInterface;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.objects.ObjectID;
import com.lapissea.cfs.objects.collections.IOList;
import com.lapissea.cfs.objects.collections.IOMap;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.annotations.IOValue;

import java.io.IOException;
import java.util.Objects;

public class IPs{
	
	
	public static class IP extends IOInstance<IP>{
		@IOValue
		double latitude, longitude;
		
		@IOValue
		String v6;
		
		//Every IOInstance needs an empty constructor
		public IP(){}
		public IP(double latitude, double longitude, String v6){
			this.latitude=latitude;
			this.longitude=longitude;
			this.v6=v6;
		}
	}
	
	public static class IPSet extends IOInstance<IPSet>{
		@IOValue
		IOList<IP> ips;
	}
	
	
	public static void main(String[] args) throws IOException{
		
		// run this instead if you have a display server running and this program has a config.json to get a real time display of what is happening
//		LoggedMemoryUtils.simpleLoggedMemorySession(IPs::run);
		
		//No need to hassle with a real file, just make an empty in ram IOInterface
		IOInterface memory=MemoryData.builder().build();
		run(memory);
	}
	
	public static void run(IOInterface memory) throws IOException{
		createData(memory);
		
		printData(memory);
	}
	
	public static void createData(IOInterface memory) throws IOException{
		//Memory needs to be initialized with an empty database.
		//Cluster.init acts as a clean slate for the memory field. Any previously contained data will be deleted
		var cluster=Cluster.init(memory);
		
		//Ask root provider for an IPSet with the id of my ips
		IPSet set=cluster.getRootProvider().request(IPSet.class, "my ips");
		
		//Nice thing to do, reduces possibility of fragmentation. This is only useful when adding element by element. addAll does not benefit from this
		set.ips.requestCapacity(2);
		
		//Adding sample data to database:
		set.ips.add(new IP(0.2213415, 0.71346, "2001:0db8:85a3:0000:0000:8a2e:0370:7334"));
		set.ips.add(new IP(0.6234, 0.51341123, "2001:0db8:0:1:1:1:1:1"));
	}
	
	
	private static void printData(IOInterface memory) throws IOException{
		System.out.println(memory.hexdump());
		System.out.println();
		System.out.println("Data in memory:");
		
		for(IOMap.Entry<ObjectID, Object> e : new Cluster(memory).getRootProvider().listAll()){
			System.out.println(e.getKey()+": "+switch(e.getValue()){
				//Just a fancy toString that every IOInstance has that allows for printing the data in a more appealing way
				case IOInstance<?> inst -> inst.toString(false, "{\n\t", "\n}", ": ", ",\n\t");
				default -> Objects.toString(e.getValue());
			});
		}
	}
}