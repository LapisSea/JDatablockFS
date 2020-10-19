package com.lapissea.cfs.cluster.extensions;

import com.lapissea.cfs.cluster.AllocateTicket;
import com.lapissea.cfs.cluster.Cluster;
import com.lapissea.cfs.cluster.PackingConfig;
import com.lapissea.cfs.io.IOInterface;
import com.lapissea.cfs.io.struct.IOInstance;
import com.lapissea.cfs.io.struct.IOStruct.PointerValue;
import com.lapissea.cfs.io.struct.IOStruct.Value;
import com.lapissea.cfs.objects.IOList;
import com.lapissea.cfs.objects.IOType;
import com.lapissea.cfs.objects.StructLinkedList;
import com.lapissea.cfs.objects.chunk.ChunkPointer;
import com.lapissea.cfs.objects.text.AutoText;

import java.io.File;
import java.io.IOException;
import java.util.Objects;

public class ObjectCluster extends Cluster{
	
	
	public static class Builder extends Cluster.Builder{
		@Override
		public ObjectCluster build() throws IOException{
			return new ObjectCluster(data, packingConfig, minChunkSize, readOnly);
		}
	}
	
	protected ObjectCluster(IOInterface data, PackingConfig packingConfig, int minChunkSize, boolean readOnly) throws IOException{
		super(data, packingConfig, minChunkSize, readOnly);
	}
	
	
}
