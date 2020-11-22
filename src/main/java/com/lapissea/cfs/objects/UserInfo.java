package com.lapissea.cfs.objects;

import com.lapissea.cfs.cluster.Cluster;
import com.lapissea.cfs.io.struct.IOInstance;
import com.lapissea.cfs.io.struct.IOStruct;
import com.lapissea.cfs.objects.chunk.Chunk;
import com.lapissea.cfs.objects.chunk.ChunkPointer;
import com.lapissea.cfs.objects.chunk.ObjectPointer;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Objects;

import static com.lapissea.cfs.GlobalConfig.*;

public class UserInfo extends IOInstance{
	
	@IOStruct.Value(index=0, rw=AutoRW.class)
	private IOTypeLayout type;
	
	@IOStruct.Value(index=1, rw=ObjectPointer.AutoSizedNoOffsetIO.class)
	private final ObjectPointer<? extends IOInstance> ptr=new ObjectPointer.Struct<>(this::typeConstruct){
		@Override
		public IOInstance read(Cluster cluster) throws IOException{
			var cached=getCached(DEBUG_VALIDATION?getBlock(cluster):null);
			if(cached!=null) return cached;
			return super.read(cluster);
		}
	};
	
	private WeakReference<IOInstance> objCache=new WeakReference<>(null);
	
	public UserInfo(){ }
	
	public UserInfo(IOTypeLayout type, ChunkPointer ptr){
		this.type=type;
		this.ptr.set(ptr, 0);
	}
	
	private IOInstance getCached(Chunk chunk) throws IOException{
		
		IOInstance val=objCache.get();
//		if(DEBUG_VALIDATION){
//			if(val!=null){
//				IOInstance readVal=chunk.cluster.getTypeParsers().parse(chunk.cluster, type).apply(chunk);
//				assert readVal.equals(val):readVal+" "+val;
//			}
//		}
		return val;
	}
	
	private IOInstance typeConstruct(Chunk chunk) throws IOException{
		IOInstance val=getCached(chunk);
		if(val==null){
			val=chunk.cluster.getTypeParsers().parse(chunk.cluster, type).apply(chunk);
			objCache=new WeakReference<>(val);
		}
		return val;
	}
	
	public ObjectPointer<? extends IOInstance> getObjPtr(){
		return ptr;
	}
	
	public ChunkPointer getPtr(){
		return getObjPtr().getDataBlock();
	}
	
	public IOTypeLayout getType(){
		return type;
	}
	
	@Override
	public boolean equals(Object o){
		if(this==o) return true;
		return o instanceof UserInfo userInfo&&
		       Objects.equals(type, userInfo.type)&&
		       Objects.equals(getPtr(), userInfo.getPtr());
	}
	
	@Override
	public int hashCode(){
		int result=1;
		result=31*result+(type==null?0:type.hashCode());
		result=31*result+(getPtr()==null?0:getPtr().hashCode());
		return result;
	}
	
	@Override
	public String toString(){
		return type+" @ "+ptr;
	}
	
	public String toShortString(){
		return type.toShortString()+" @ "+ptr;
	}
}
