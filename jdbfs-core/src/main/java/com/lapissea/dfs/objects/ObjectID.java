package com.lapissea.dfs.objects;

import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.annotations.IODependency;
import com.lapissea.dfs.type.field.annotations.IOValue;

import java.util.Objects;

public sealed interface ObjectID{
	
	static ObjectID of(String id){ return new SID(id); }
	static ObjectID of(long id)  { return new LID(id); }
	static ObjectID of(byte id)  { return new BID(id); }
	
	@IOValue
	@IOInstance.StrFormat.Custom("@id")
	final class SID extends IOInstance.Managed<SID> implements ObjectID{
		
		private String id;
		
		public SID(){ }
		public SID(String id){
			this.id = Objects.requireNonNull(id);
		}
	}
	
	@IOValue
	@IOInstance.StrFormat.Custom("@id")
	final class LID extends IOInstance.Managed<LID> implements ObjectID{
		
		@IODependency.VirtualNumSize
		private long id;
		
		public LID(){ }
		public LID(long id){
			this.id = id;
		}
	}
	
	@IOValue
	@IOInstance.StrFormat.Custom("@id")
	final class BID extends IOInstance.Managed<BID> implements ObjectID{
		
		private byte id;
		
		public BID(){ }
		public BID(byte id){
			this.id = id;
		}
	}
	
	ObjectID clone();
}
