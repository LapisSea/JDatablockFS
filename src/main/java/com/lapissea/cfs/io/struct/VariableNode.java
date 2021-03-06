package com.lapissea.cfs.io.struct;

import com.lapissea.cfs.cluster.Cluster;
import com.lapissea.cfs.io.SelfPoint;
import com.lapissea.cfs.io.bit.BitReader;
import com.lapissea.cfs.io.bit.BitWriter;
import com.lapissea.cfs.io.bit.FlagReader;
import com.lapissea.cfs.io.bit.FlagWriter;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.util.TextUtil;

import java.io.IOException;
import java.lang.reflect.Array;
import java.util.AbstractMap.SimpleEntry;
import java.util.List;
import java.util.OptionalLong;
import java.util.stream.IntStream;

public abstract class VariableNode<ValTyp>{
	
	public interface FixedSize{
		
		abstract class Node<ValTyp> extends VariableNode<ValTyp> implements FixedSize{
			
			private final int size;
			
			protected Node(VarInfo info, int size){
				super(info);
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
			public long mapSize(IOInstance target, ValTyp value){
				return getSize();
			}
			
			/**
			 * Use {@link FixedSize#getSize()}
			 */
			@Deprecated
			@Override
			public long mapSize(IOInstance target){ return getSize(); }
			
			@Override
			public OptionalLong getMaximumSize(){
				return OptionalLong.of(getSize());
			}
		}
		
		
		long getSize();
		
		static long getSizeUnknown(IOInstance target, VariableNode<?> v){
			return v instanceof FixedSize f?f.getSize():v.mapSize(target);
		}
	}
	
	public abstract static class Flag<ValTyp> extends FixedSize.Node<ValTyp>{
		
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
		
		protected Flag(VarInfo info, int bitSize, int paddingBits){
			super(info, -1);
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
		
		protected abstract ValTyp readData(IOInstance target, BitReader source, ValTyp oldVal) throws IOException;
		
		protected abstract void writeData(IOInstance target, BitWriter dest, ValTyp source) throws IOException;
		
		@Deprecated
		@Override
		public ValTyp read(IOInstance target, ContentReader source, ValTyp oldVal, Cluster cluster) throws IOException{
			try(var flags=FlagReader.read(source, individualSize)){
				return readData(target, flags, oldVal);
			}
		}
		
		@Deprecated
		@Override
		public void write(IOInstance target, Cluster cluster, ContentWriter dest, ValTyp source) throws IOException{
			try(var flags=new FlagWriter.AutoPop(individualSize, dest)){
				writeData(target, flags, source);
			}
		}
		
		
		public final void write(IOInstance target, FlagWriter dest) throws IOException{
			ValTyp source=getValue(target);
			writeData(target, dest, source);
			dest.fillNOne(paddingBits);
		}
		
		public final void read(IOInstance target, FlagReader source) throws IOException{
			var oldVal=getValue(target);
			var newVal=readData(target, source, oldVal);
			source.checkNOneAndThrow(paddingBits);
			setValue(target, newVal);
		}
		
	}
	
	private abstract static class Primitive<T> extends VariableNode<T>{
		
		protected Primitive(VarInfo info){
			super(info);
		}
		
		@Override
		public abstract void read(IOInstance target, Cluster cluster, ContentReader source) throws IOException;
		
		@Override
		public abstract void write(IOInstance target, Cluster cluster, ContentWriter dest) throws IOException;
		
		@Override
		public abstract long mapSize(IOInstance target);
		
		@Override
		public abstract void setValueAsObj(IOInstance target, T value);
		
		@Deprecated
		@Override
		public final T getValue(IOInstance source){ throw new UnsupportedOperationException(toString()); }
		
		@Deprecated
		@Override
		protected final void setValue(IOInstance target, T newValue){ throw new UnsupportedOperationException(toString()); }
		
		@Deprecated
		@Override
		public final T read(IOInstance target, ContentReader source, T oldVal, Cluster cluster){ throw new UnsupportedOperationException(toString()); }
		
		@Deprecated
		@Override
		protected final void write(IOInstance target, Cluster cluster, ContentWriter dest, T source){ throw new UnsupportedOperationException(toString()); }
		
		@Deprecated
		@Override
		public long mapSize(IOInstance target, T value){ throw new UnsupportedOperationException(toString());}
		
	}
	
	public abstract static class ArrayVar<T> extends VariableNode<T>{
		
		public abstract static class Fixed<T> extends ArrayVar<T> implements FixedSize{
			
			protected Fixed(VarInfo info, int fixedElementSize){
				super(info, fixedElementSize);
				assert fixedElementSize>=0;
			}
			
			@Override
			protected final long getElementsSize(IOInstance target, T value){
				return getFixedElementsSize();
			}
			
			protected abstract long getFixedElementsSize();
			
			@Override
			public long getSize(){
				return fixedArraySize*getFixedElementsSize();
			}
		}
		
		
		protected final int fixedArraySize;
		
		protected ArrayVar(VarInfo info, int fixedArraySize){
			super(info);
			this.fixedArraySize=fixedArraySize;
		}
		
		protected abstract T makeArray(int size);
		
		protected abstract long getElementsSize(IOInstance target, T value);
		
		protected abstract void readElements(Cluster cluster, IOInstance target, ContentReader source, T array) throws IOException;
		
		protected abstract void writeElements(Cluster cluster, IOInstance target, ContentWriter dest, T array) throws IOException;
		
		private boolean fixedSize(){
			return fixedArraySize!=-1;
		}
		
		@Override
		protected final long mapSize(IOInstance target, T value){
			return (fixedSize()?0:headSize(value))+getElementsSize(target, value);
		}
		
		@Override
		public final T read(IOInstance target, ContentReader source, T oldVal, Cluster cluster) throws IOException{
			int siz  =fixedSize()?fixedArraySize:readSize(source);
			T   array=makeArray(siz);
			readElements(cluster, target, source, array);
			return array;
		}
		
		@Override
		public final void write(IOInstance target, Cluster cluster, ContentWriter dest, T array) throws IOException{
			if(!fixedSize()) writeSize(dest, array);
			writeElements(cluster, target, dest, array);
		}
		
		protected int readSize(ContentReader source) throws IOException{
			return Math.toIntExact(NumberSize.INT.read(source));
		}
		protected void writeSize(ContentWriter dest, T array) throws IOException{
			NumberSize.INT.write(dest, Array.getLength(array));
		}
		protected int headSize(T array){
			return NumberSize.INT.bytes;
		}
	}
	
	public abstract static class PrimitiveLong extends Primitive<Long>{
		
		protected PrimitiveLong(VarInfo info){
			super(info);
		}
		
		protected abstract long get(IOInstance source);
		protected abstract void set(IOInstance target, long newValue);
		protected abstract long read(IOInstance target, ContentReader source, long oldVal) throws IOException;
		protected abstract void write(IOInstance target, ContentWriter dest, long source) throws IOException;
		
		@Override
		public void read(IOInstance target, Cluster cluster, ContentReader source) throws IOException{
			var oldVal=get(target);
			var newVal=read(target, source, oldVal);
			set(target, newVal);
		}
		
		@Override
		public void write(IOInstance target, Cluster cluster, ContentWriter dest) throws IOException{
			write(target, dest, get(target));
		}
		
		@Override
		public Long getValueAsObj(IOInstance source){
			return get(source);
		}
		
		@Override
		public void setValueAsObj(IOInstance target, Long value){
			set(target, value);
		}
	}
	
	public abstract static class PrimitiveInt extends Primitive<Integer>{
		
		protected PrimitiveInt(VarInfo info){
			super(info);
		}
		
		protected abstract int get(IOInstance source);
		protected abstract void set(IOInstance target, int newValue);
		protected abstract int read(IOInstance target, ContentReader source, int oldVal) throws IOException;
		protected abstract void write(IOInstance target, ContentWriter dest, int source) throws IOException;
		
		@Override
		public void read(IOInstance target, Cluster cluster, ContentReader source) throws IOException{
			var oldVal=get(target);
			var newVal=read(target, source, oldVal);
			set(target, newVal);
		}
		
		@Override
		public void write(IOInstance target, Cluster cluster, ContentWriter dest) throws IOException{
			write(target, dest, get(target));
		}
		
		@Override
		public Integer getValueAsObj(IOInstance source){
			return get(source);
		}
		
		@Override
		public void setValueAsObj(IOInstance target, Integer value){
			set(target, value);
		}
	}
	
	public abstract static class PrimitiveFloat extends Primitive<Float>{
		
		protected PrimitiveFloat(VarInfo info){
			super(info);
		}
		
		protected abstract float get(IOInstance source);
		protected abstract void set(IOInstance target, float newValue);
		protected abstract float read(IOInstance target, ContentReader source, float oldVal) throws IOException;
		protected abstract void write(IOInstance target, ContentWriter dest, float source) throws IOException;
		
		@Override
		public void read(IOInstance target, Cluster cluster, ContentReader source) throws IOException{
			var oldVal=get(target);
			var newVal=read(target, source, oldVal);
			set(target, newVal);
		}
		
		@Override
		public void write(IOInstance target, Cluster cluster, ContentWriter dest) throws IOException{
			write(target, dest, get(target));
		}
		
		@Override
		public Float getValueAsObj(IOInstance source){
			return get(source);
		}
		
		@Override
		public void setValueAsObj(IOInstance target, Float value){
			set(target, value);
		}
	}
	
	public abstract static class SelfPointer<T extends IOInstance&SelfPoint<T>> extends VariableNode<T>{
		
		protected SelfPointer(VarInfo info){
			super(info);
		}
		
		public void allocNew(IOInstance target, Cluster cluster) throws IOException{
			allocNew(target, cluster, false);
		}
		
		public abstract void allocNew(IOInstance target, Cluster cluster, boolean disableNext) throws IOException;
	}
	
	public static record VarInfo(String name, int index){}
	
	public final VarInfo info;
	
	private Offset knownOffset;
	
	protected VariableNode(VarInfo info){
		this.info=info;
	}
	
	public ValTyp getValueAsObj(IOInstance source){
		return getValue(source);
	}
	
	public void setValueAsObj(IOInstance target, ValTyp value){
		setValue(target, value);
	}
	
	protected abstract ValTyp getValue(IOInstance source);
	
	protected abstract void setValue(IOInstance target, ValTyp newValue);
	
	protected abstract long mapSize(IOInstance target, ValTyp value);
	
	public abstract ValTyp read(IOInstance target, ContentReader source, ValTyp oldVal, Cluster cluster) throws IOException;
	
	protected abstract void write(IOInstance target, Cluster cluster, ContentWriter dest, ValTyp source) throws IOException;
	
	
	public void read(IOInstance target, Cluster cluster, ContentReader source) throws IOException{
		var oldVal=getValue(target);
		var newVal=read(target, source, oldVal, cluster);
		setValue(target, newVal);
	}
	
	public void write(IOInstance target, Cluster cluster, ContentWriter dest) throws IOException{
		write(target, cluster, dest, getValue(target));
	}
	
	public long mapSize(IOInstance target){
		return mapSize(target, getValue(target));
	}
	
	
	protected void postProcess(int index, List<VariableNode<Object>> variables){ }
	
	public void applyKnownOffset(Offset offset){
		this.knownOffset=offset;
	}
	
	public Offset getKnownOffset(){
		return knownOffset;
	}
	
	public SimpleEntry<String, Object> toString(IOInstance instance){
		return new SimpleEntry<>(info.name, getValueAsObj(instance));
	}
	
	@Override
	public String toString(){
		var cName=this.getClass().getName();
		cName=cName.substring(cName.lastIndexOf('.')+1).replace("IOImpl$", ">");
		StringBuilder sb =new StringBuilder(cName);
		var           end="IOImpl";
		if(cName.endsWith(end)) sb.setLength(sb.length()-end.length());
		sb.append('{');
		sb.append(info.name);
		if(getKnownOffset()!=null) sb.append(", offset = ").append(getKnownOffset());
		if(this instanceof FixedSize fixed) sb.append(", size = ").append(fixed.getSize());
		sb.append('}');
		
		return sb.toString();
	}
	
	public OptionalLong getMaximumSize(){
		return OptionalLong.empty();
	}
}
