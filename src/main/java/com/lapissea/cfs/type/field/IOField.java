package com.lapissea.cfs.type.field;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.io.bit.BitInputStream;
import com.lapissea.cfs.io.bit.BitOutputStream;
import com.lapissea.cfs.io.bit.BitReader;
import com.lapissea.cfs.io.bit.BitWriter;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.cfs.type.field.access.IFieldAccessor;
import com.lapissea.util.TextUtil;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.stream.Collectors;

import static com.lapissea.cfs.GlobalConfig.*;

public abstract class IOField<T extends IOInstance<T>, ValueType>{
	
	static{
		TextUtil.SHORT_TO_STRINGS.register(OptionalLong.class, l->l.isEmpty()?"()L":"("+l.getAsLong()+")L");
	}
	
	public abstract static class Bit<T extends IOInstance<T>, Type> extends IOField<T, Type>{
		
		protected Bit(IFieldAccessor<T> field){
			super(field);
		}
		@Override
		public WordSpace getWordSpace(){
			return WordSpace.BIT;
		}
		
		@Deprecated
		@Override
		public final void write(ContentWriter dest, T instance) throws IOException{
			try(var writer=new BitOutputStream(dest)){
				writeBits(writer, instance);
				if(DEBUG_VALIDATION){
					writer.requireWritten(calcSize(instance));
				}
			}
		}
		
		@Deprecated
		@Override
		public final void read(ContentReader src, T instance) throws IOException{
			try(var reader=new BitInputStream(src)){
				readBits(reader, instance);
				if(DEBUG_VALIDATION){
					reader.requireRead(calcSize(instance));
				}
			}
		}
		
		public abstract void writeBits(BitWriter<?> dest, T instance) throws IOException;
		public abstract void readBits(BitReader src, T instance) throws IOException;
	}
	
	private final IFieldAccessor<T> accessor;
	
	private List<IOField<T, ?>> dependencies;
	
	protected IOField(IFieldAccessor<T> accessor){
		this.accessor=accessor;
	}
	
	public IFieldAccessor<T> getAccessor(){
		return accessor;
	}
	
	public void initCommon(List<IOField<T, ?>> deps){
		Objects.requireNonNull(deps);
		Utils.requireNull(dependencies);
		dependencies=deps;
	}
	
	public WordSpace getWordSpace(){
		return WordSpace.BYTE;
	}
	
	public List<IOField<T, ?>> getDependencies(){
		return dependencies;
	}
	
	public String getName(){return getAccessor().getName();}
	
	public String toShortString(){
		return "{"+
		       Objects.requireNonNull(getName())+
		       switch(getDependencies().size()){
			       case 0 -> "";
			       case 1 -> ", dep: "+getDependencies().get(0).getName();
			       default -> getDependencies().stream().map(IOField::getName).collect(Collectors.joining(",", ", deps: ", ""));
		       }+
		       '}';
	}
	@Override
	public String toString(){
		return this.getClass().getSimpleName()+toShortString();
	}
	
	public ValueType get(T instance){
		return (ValueType)getAccessor().get(instance);
	}
	
	public void set(T instance, ValueType value){
		getAccessor().set(instance, value);
	}
	
	public final long calcByteSize(T instance){
		var size=calcSize(instance);
		return switch(getWordSpace()){
			case BIT -> Utils.bitToByte(size);
			case BYTE -> size;
		};
	}
	
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
	
	public boolean instancesEqual(T inst1, T inst2){
		return Objects.equals(get(inst1), get(inst2));
	}
	
	public int instanceHashCode(T instance){
		return Objects.hashCode(get(instance));
	}
	
	public void init(){
		getAccessor().init(this);
	}
}
