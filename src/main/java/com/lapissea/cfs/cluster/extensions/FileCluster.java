package com.lapissea.cfs.cluster.extensions;

import com.lapissea.cfs.cluster.Cluster;
import com.lapissea.cfs.conf.AllocateTicket;
import com.lapissea.cfs.io.struct.IOInstance;
import com.lapissea.cfs.io.struct.IOStruct.PointerValue;
import com.lapissea.cfs.io.struct.IOStruct.Value;
import com.lapissea.cfs.objects.IOList;
import com.lapissea.cfs.objects.IOType;
import com.lapissea.cfs.objects.StructLinkedList;
import com.lapissea.cfs.objects.chunk.ChunkPointer;
import com.lapissea.cfs.objects.text.AutoText;

import java.io.IOException;
import java.util.Objects;

public class FileCluster{
	
	private static class File extends IOInstance{
		@Value(index=0, rw=AutoText.StringIO.class)
		private String name;
		
		@Value(index=1, rw=ChunkPointer.FixedIO.class)
		private ChunkPointer data;
		
		public File(String name){
			this.name=name;
		}
		
		public File(String name, ChunkPointer data){
			this.name=name;
			this.data=data;
		}
		
		@Override
		public boolean equals(Object o){
			if(this==o) return true;
			return o instanceof File file&&
			       Objects.equals(name, file.name)&&
			       Objects.equals(data, file.data);
		}
		
		@Override
		public int hashCode(){
			return Objects.hash(name, data);
		}
	}
	
	private static class Folder extends IOInstance{
		
		@Value(index=0, rw=AutoText.StringIO.class)
		private String name;
		
		@PointerValue(index=1, type=StructLinkedList.class)
		private IOList<Folder> children;
		
		@PointerValue(index=2, type=StructLinkedList.class)
		private IOList<File> files;
		
		public Folder(String name){
			this.name=name;
		}
		
		void addChild(Cluster cluster, Folder folder) throws IOException{
			if(children==null){
				cluster.constructType(new IOType(StructLinkedList.class, Folder.class), AllocateTicket.fitTo(StructLinkedList.class));
			}
			if(children.contains(folder)) return;
			children.addElement(folder);
		}
		
		@Override
		public boolean equals(Object o){
			if(this==o) return true;
			return o instanceof Folder folder&&
			       Objects.equals(name, folder.name)&&
			       Objects.equals(children, folder.children)&&
			       Objects.equals(files, folder.files);
		}
		
		@Override
		public int hashCode(){
			return Objects.hash(name, children, files);
		}
	}
	
	@PointerValue(index=4)
	private Folder rootFolder;
	
	
	protected FileCluster() throws IOException{
	}
	
	
}
