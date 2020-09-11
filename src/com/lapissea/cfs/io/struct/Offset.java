package com.lapissea.cfs.io.struct;

import com.lapissea.util.NotNull;

public sealed interface Offset{
	
	record ByteOffset(long offset) implements Offset{
		@Override
		public long getOffset(){
			return offset();
		}
		@Override
		public long getBitOffset(){
			return offset()*Byte.SIZE;
		}
		
		@Override
		public boolean equals(Object o){ return this==o||o instanceof Offset off&&equals(off); }
		
		@Override
		public String toString(){ return toShortString(); }
	}
	
	record BitOffset(long bitOffset) implements Offset{
		@Override
		public long getOffset(){
			return bitOffset()/Byte.SIZE;
		}
		@Override
		public long getBitOffset(){
			return bitOffset();
		}
		
		@Override
		public boolean equals(Object o){ return this==o||o instanceof Offset off&&equals(off); }
		
		@Override
		public String toString(){ return toShortString(); }
	}
	
	static Offset fromBytes(long bytes){ return new ByteOffset(bytes); }
	static Offset fromBits(long bits){
		var bi=new BitOffset(bits);
		var by=new ByteOffset(bi.getOffset());
		return bi.equals(by)?by:bi;
	}
	
	Offset ZERO=fromBytes(0);
	
	long getOffset();
	long getBitOffset();
	
	default Offset add(@NotNull Offset other){
		return fromBits(getBitOffset()+other.getBitOffset());
	}
	
	default boolean equals(Offset off){
		return this==off||getBitOffset()==off.getBitOffset();
	}
	
	
	default String toShortString(){
		var bits =getBitOffset();
		var bytes=bits/Byte.SIZE;
		bits-=bytes*Byte.SIZE;
		if(bits==0) return bytes+"b";
		return bytes+"b + "+bits;
	}
}
