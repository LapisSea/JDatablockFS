package com.lapissea.cfs.io.struct;

import com.lapissea.cfs.io.bit.FlagReader;
import com.lapissea.cfs.io.bit.FlagWriter;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.util.TextUtil;

import java.io.IOException;
import java.util.AbstractMap.SimpleEntry;
import java.util.List;
import java.util.OptionalLong;
import java.util.stream.IntStream;

public abstract class VariableNode<ValTyp>{
	
	public interface FixedSize{
		
		abstract class Node<ValTyp> extends VariableNode<ValTyp> implements FixedSize{
			
			private final int size;
			protected Node(String name, int index, int size){
				super(name, index);
				this.size=size;
			}
			
			@Override
			public long getSize(){
				return size;
			}
			
			/**
			 * Use {@link FixedSize#getSize()}
			 */
			@Deprecated
			@Override
			public long mapSize(IOStruct.Instance target, ValTyp value){
				return getSize();
			}
			
			/**
			 * Use {@link FixedSize#getSize()}
			 */
			@Deprecated
			@Override
			public long mapSize(IOStruct.Instance target){ return getSize(); }
			
			@Override
			public OptionalLong getMaximumSize(){
				return OptionalLong.of(getSize());
			}
		}
		
		
		long getSize();
		
		static long getSizeUnknown(IOStruct.Instance target, VariableNode<?> v){
			return v instanceof FixedSize f?f.getSize():v.mapSize(target);
		}
	}
	
	public abstract static class Flag<ValTyp> extends FixedSize.Node<ValTyp>{
		
		private static final float MIN_PADDING_RATIO=0;
		
		public record FlagBlock(int start, int end, int bitSum, NumberSize wordSize){
			public int count(){
				return end-start;
			}
			
			public IntStream range(){
				return IntStream.range(start, end);
			}
		}
		
		private FlagBlock floodFlags(List<VariableNode<Object>> variables, int start){
			
			int bitSum =0;
			int maxBits=NumberSize.LONG.bytes*Byte.SIZE;
			
			int end=start;
			for(;end<variables.size();end++){
				
				VariableNode<Object> v1=variables.get(end);
				if(v1 instanceof Flag<?> f2){
					
					var bs=f2.bitSize+f2.paddingBits;
					if(bs<1) throw new IllegalStateException(bs+" bits in "+TextUtil.toString(v1));
					if(bitSum+bs>maxBits){
						end--;
						break;
					}
					bitSum+=bs;
					
				}else{
//					end--;
					break;
				}
			}
			
			return new FlagBlock(start, end, bitSum, NumberSize.byBits((end-start)==1?bitSum:bitSum+1));
		}
		
		private final int bitSize;
		protected     int paddingBits;
		
		private FlagBlock  blockInfo;
		private NumberSize individualSize;
		
		protected Flag(String name, int index, int bitSize, int paddingBits){
			super(name, index, -1);
			this.bitSize=bitSize;
			this.paddingBits=paddingBits;
		}
		
		@Override
		protected void postProcess(int index, List<VariableNode<Object>> variables){
			try{
				if(index>0&&variables.get(index-1) instanceof VariableNode.Flag) return;
				
				blockInfo=floodFlags(variables, index);
				
				var targetedSize=blockInfo.wordSize().bits();
				var freeSpace   =targetedSize-blockInfo.bitSum;
				if(freeSpace>0){
					var count=blockInfo.end-blockInfo.start;
					for(int i=0;i<freeSpace;i++){
						((Flag<?>)variables.get(blockInfo.end-1-(i%count))).paddingBits++;
					}
					blockInfo=new FlagBlock(blockInfo.start, blockInfo.end, targetedSize, blockInfo.wordSize());
				}
			}finally{
				individualSize=NumberSize.byBits(getTotalBits());
			}
		}
		
		public void clearBlockInfo(){
			blockInfo=null;
		}
		
		public FlagBlock getBlockInfo(){
			return blockInfo;
		}
		
		@Override
		public long getSize(){
			return individualSize.bytes;
		}
		
		public int getBitSize(){
			return bitSize;
		}
		
		public int getPaddingBits(){
			return paddingBits;
		}
		
		public int getTotalBits(){
			return bitSize+paddingBits;
		}
		
		protected abstract ValTyp readData(IOStruct.Instance target, FlagReader source, ValTyp oldVal);
		protected abstract void writeData(IOStruct.Instance target, FlagWriter dest, ValTyp source);
		
		@Deprecated
		@Override
		public ValTyp read(IOStruct.Instance target, ContentReader source, ValTyp oldVal) throws IOException{
			try(var flags=FlagReader.read(source, individualSize)){
				return readData(target, flags, oldVal);
			}
		}
		
		@Deprecated
		@Override
		public void write(IOStruct.Instance target, ContentWriter dest, ValTyp source) throws IOException{
			try(var flags=new FlagWriter.AutoPop(individualSize, dest)){
				writeData(target, flags, source);
			}
		}
		
		
		public final void write(IOStruct.Instance target, FlagWriter dest){
			ValTyp source=getValue(target);
			writeData(target, dest, source);
			dest.fillNOne(paddingBits);
		}
		
		public final void read(IOStruct.Instance target, FlagReader source){
			var oldVal=getValue(target);
			var newVal=readData(target, source, oldVal);
			source.checkNOneAndThrow(paddingBits);
			setValue(target, newVal);
		}
		
	}
	
	private abstract static class Primitive<T> extends VariableNode<T>{
		
		protected Primitive(String name, int index){
			super(name, index);
		}
		
		@Override
		public abstract void read(IOStruct.Instance target, ContentReader source) throws IOException;
		
		@Override
		public abstract void write(IOStruct.Instance target, ContentWriter dest) throws IOException;
		
		@Override
		public abstract long mapSize(IOStruct.Instance target);
		
		@Deprecated
		@Override
		protected final T getValue(IOStruct.Instance source){ throw new UnsupportedOperationException(toString()); }
		@Deprecated
		@Override
		protected final void setValue(IOStruct.Instance target, T newValue){ throw new UnsupportedOperationException(toString()); }
		@Deprecated
		@Override
		protected final T read(IOStruct.Instance target, ContentReader source, T oldVal){ throw new UnsupportedOperationException(toString()); }
		@Deprecated
		@Override
		protected final void write(IOStruct.Instance target, ContentWriter dest, T source){ throw new UnsupportedOperationException(toString()); }
		@Deprecated
		@Override
		public long mapSize(IOStruct.Instance target, T value){ throw new UnsupportedOperationException(toString());}
		
	}
	
	public abstract static class PrimitiveLong extends Primitive<Long>{
		
		protected PrimitiveLong(String name, int index){
			super(name, index);
		}
		protected abstract long get(IOStruct.Instance source);
		protected abstract void set(IOStruct.Instance target, long newValue);
		protected abstract long read(IOStruct.Instance target, ContentReader source, long oldVal) throws IOException;
		protected abstract void write(IOStruct.Instance target, ContentWriter dest, long source) throws IOException;
		
		@Override
		public void read(IOStruct.Instance target, ContentReader source) throws IOException{
			var oldVal=get(target);
			var newVal=read(target, source, oldVal);
			set(target, newVal);
		}
		
		@Override
		public void write(IOStruct.Instance target, ContentWriter dest) throws IOException{
			write(target, dest, get(target));
		}
		
		@SuppressWarnings("AutoBoxing")
		@Override
		public SimpleEntry<String, Object> toString(IOStruct.Instance instance){
			return new SimpleEntry<>(name, get(instance));
		}
	}
	
	public abstract static class PrimitiveInt extends Primitive<Integer>{
		
		protected PrimitiveInt(String name, int index){
			super(name, index);
		}
		protected abstract int get(IOStruct.Instance source);
		protected abstract void set(IOStruct.Instance target, int newValue);
		protected abstract int read(IOStruct.Instance target, ContentReader source, int oldVal) throws IOException;
		protected abstract void write(IOStruct.Instance target, ContentWriter dest, int source) throws IOException;
		
		@Override
		public void read(IOStruct.Instance target, ContentReader source) throws IOException{
			var oldVal=get(target);
			var newVal=read(target, source, oldVal);
			set(target, newVal);
		}
		
		@Override
		public void write(IOStruct.Instance target, ContentWriter dest) throws IOException{
			write(target, dest, get(target));
		}
		
		@SuppressWarnings("AutoBoxing")
		@Override
		public SimpleEntry<String, Object> toString(IOStruct.Instance instance){
			return new SimpleEntry<>(name, get(instance));
		}
	}
	
	public final String name;
	public final int    index;
	
	private Offset knownOffset;
	
	protected VariableNode(String name, int index){
		this.name=name;
		this.index=index;
	}
	
	protected abstract ValTyp getValue(IOStruct.Instance source);
	
	protected abstract void setValue(IOStruct.Instance target, ValTyp newValue);
	
	protected abstract long mapSize(IOStruct.Instance target, ValTyp value);
	
	protected abstract ValTyp read(IOStruct.Instance target, ContentReader source, ValTyp oldVal) throws IOException;
	
	protected abstract void write(IOStruct.Instance target, ContentWriter dest, ValTyp source) throws IOException;
	
	
	public void read(IOStruct.Instance target, ContentReader source) throws IOException{
		var oldVal=getValue(target);
		var newVal=read(target, source, oldVal);
		setValue(target, newVal);
	}
	
	public void write(IOStruct.Instance target, ContentWriter dest) throws IOException{
		write(target, dest, getValue(target));
	}
	
	public long mapSize(IOStruct.Instance target){
		return mapSize(target, getValue(target));
	}
	
	
	protected void postProcess(int index, List<VariableNode<Object>> variables){ }
	
	public void applyKnownOffset(Offset offset){
		this.knownOffset=offset;
	}
	
	public Offset getKnownOffset(){
		return knownOffset;
	}
	
	public SimpleEntry<String, Object> toString(IOStruct.Instance instance){
		return new SimpleEntry<>(name, getValue(instance));
	}
	
	@Override
	public String toString(){
		StringBuilder sb=new StringBuilder(this.getClass().getSimpleName());
		sb.append('{');
		sb.append(name);
		if(getKnownOffset()!=null) sb.append(", offset = ").append(getKnownOffset());
		if(this instanceof FixedSize fixed) sb.append(", size = ").append(fixed.getSize());
		sb.append('}');
		
		return sb.toString();
	}
	
	public OptionalLong getMaximumSize(){
		return OptionalLong.empty();
	}
}
