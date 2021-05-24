package com.lapissea.cfs.type;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.io.bit.BitInputStream;
import com.lapissea.cfs.io.bit.BitOutputStream;
import com.lapissea.cfs.io.bit.BitReader;
import com.lapissea.cfs.io.bit.BitWriter;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.util.TextUtil;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

public abstract class IOField<T extends IOInstance<T>, Type>{
	
	public abstract static class Bit<T extends IOInstance<T>, Type> extends IOField<T, Type>{
		@Override
		public WordSpace getWordSpace(){
			return WordSpace.BIT;
		}
		
		@Deprecated
		@Override
		public final void write(ContentWriter dest, T instance) throws IOException{
			try(var writer=new BitOutputStream(dest)){
				writeBits(writer, instance);
			}
		}
		
		@Deprecated
		@Override
		public final void read(ContentReader src, T instance) throws IOException{
			try(var reader=new BitInputStream(src)){
				readBits(reader, instance);
			}
		}
		
		public abstract void writeBits(BitWriter<?> dest, T instance) throws IOException;
		public abstract void readBits(BitReader src, T instance) throws IOException;
	}
	
	private List<IOField<T, ?>> deps;
	private int                 index=-1;
	
	public void initCommon(List<IOField<T, ?>> deps, int index){
		Objects.requireNonNull(deps);
		Utils.requireNull(this.deps);
		if(index==-1) throw new IndexOutOfBoundsException(index);
		this.index=index;
		this.deps=deps;
	}
	
	public WordSpace getWordSpace(){
		return WordSpace.BYTE;
	}
	
	public List<IOField<T, ?>> getDeps(){
		return deps;
	}
	
	public int getIndex(){
		return index;
	}
	
	public String getName()    {return null;}
	public String getNameOrId(){return getName()==null?getIndex()+"":getName();}
	
	public String toShortString(){
		if(index==-1) return "{uninitialized}";
		
		return "{@"+index+"="+
		       Optional.ofNullable(getName()).orElse("<unnamed>")+
		       switch(getDeps().size()){
			       case 0 -> "";
			       case 1 -> ", dep: "+TextUtil.toShortString(deps.get(0));
			       default -> ", deps: "+TextUtil.toString(deps);
		       }+
		       '}';
	}
	@Override
	public String toString(){
		return this.getClass().getSimpleName()+toShortString();
	}
	
	public abstract Type get(T instance);
	public abstract void set(T instance, Type value);
	
	public abstract long calcSize(T instance);
	public abstract OptionalLong getFixedSize();
	
	public abstract void write(ContentWriter dest, T instance) throws IOException;
	public abstract void read(ContentReader src, T instance) throws IOException;
	
	/**
	 * @return string of the resolved value or null if string has no substance
	 */
	public String instanceToString(T instance, boolean doShort){
		var val=get(instance);
		if(val==null) return null;
		return doShort?TextUtil.toShortString(val):TextUtil.toString(val);
	}
}
