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
	final class SID extends IOInstance.Managed<SID> implements ObjectID{
		
		private String id;
		
		public SID(){ }
		public SID(String id){
			this.id = Objects.requireNonNull(id);
		}
		
		@Override
		public String toString(){
			return id;
		}
		@Override
		public String toShortString(){
			return id;
		}
	}
	
	@IOValue
	final class LID extends IOInstance.Managed<LID> implements ObjectID{
		
		@IODependency.VirtualNumSize
		private long id;
		
		public LID(){ }
		public LID(long id){
			this.id = id;
		}
		
		@Override
		public String toString(){
			return id + "";
		}
		
		@Override
		public String toShortString(){
			var s = "H" + Long.toHexString(id);
			var l = toString();
			return s.length()>=l.length()? l : s;
		}
	}
	
	@IOValue
	final class BID extends IOInstance.Managed<BID> implements ObjectID{
		
		private byte id;
		
		public BID(){ }
		public BID(byte id){
			this.id = id;
		}
		
		@Override
		public String toString(){
			return id + "";
		}
		
		@Override
		public String toShortString(){
			return Integer.toHexString(Byte.toUnsignedInt(id));
		}
	}
	
	ObjectID clone();
}
