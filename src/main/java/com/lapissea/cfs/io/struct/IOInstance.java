package com.lapissea.cfs.io.struct;

import com.lapissea.cfs.cluster.Cluster;
import com.lapissea.cfs.exceptions.MalformedObjectException;
import com.lapissea.cfs.exceptions.OutOfSyncDataException;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.io.ReaderWriter;
import com.lapissea.cfs.io.SelfPoint;
import com.lapissea.cfs.io.content.ContentInputStream;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.content.SimpleContentWriter;
import com.lapissea.cfs.io.struct.engine.impl.BitBlockNode;
import com.lapissea.cfs.objects.chunk.Chunk;
import com.lapissea.cfs.objects.chunk.ObjectPointer;
import com.lapissea.util.NotNull;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;
import com.lapissea.util.function.UnsafeFunction;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.lapissea.cfs.GlobalConfig.*;
import static java.util.stream.Collectors.*;

public class IOInstance{
	
	public abstract static class Contained extends IOInstance{
		
		public abstract static class SingletonChunk<SELF extends SingletonChunk<SELF>> extends Contained implements SelfPoint<SELF>{
			
			private ObjectPointer<SELF> ptrCache;
			
			public SingletonChunk(){ }
			
			public SingletonChunk(IOStruct struct){
				super(struct);
			}
			
			public abstract Chunk getContainer();
			
			@Override
			protected RandomIO getStructSourceIO() throws IOException{
				return getContainer().io();
			}
			
			@Override
			protected Cluster getSourceCluster(){
				return getContainer().cluster;
			}
			
			protected UnsafeFunction<Chunk, SELF, IOException> getSelfConstructor(){
				return null;
			}
			
			@NotNull
			@Override
			public synchronized ObjectPointer<SELF> getSelfPtr(){
				if(ptrCache==null) ptrCache=new ObjectPointer.Struct<>(getContainer().getPtr(), 0, getSelfConstructor());
				return ptrCache;
			}
		}
		
		public Contained(){ }
		
		public Contained(IOStruct struct){
			super(struct);
		}
		
		protected abstract RandomIO getStructSourceIO() throws IOException;
		
		protected abstract Cluster getSourceCluster() throws IOException;
		
		public void readStruct() throws IOException{
			try(RandomIO buff=getStructSourceIO()){
				readStruct(getSourceCluster(), buff);
			}
		}
		
		public void writeStruct() throws IOException{
			writeStruct(false);
		}
		
		public void writeStruct(boolean trimAfterWrite) throws IOException{
			var buff=getStructSourceIO();
			try{
				this.writingInstance=true;
				try{
					buff.ensureCapacity(buff.getPos()+this.getInstanceSize());
				}finally{
					this.writingInstance=false;
				}
				writeStruct(getSourceCluster(), buff);
				if(trimAfterWrite) buff.trim();
			}catch(IOException e){
				throw new IOException("Failed to write struct "+this, e);
			}finally{
				buff.close();
			}
		}
		
		public void validateWrittenData() throws IOException{
			try(var buff=getStructSourceIO()){
				validateWrittenData(getSourceCluster(), buff);
			}
		}
	}
	
	public static class AutoRWEmptyNull<T extends IOInstance> extends AutoRW<T>{
		
		public AutoRWEmptyNull(Type targetType){
			super(targetType);
		}
		
		@Override
		public T read(Object targetObj, Cluster cluster, ContentReader source, T oldValue) throws IOException{
			if(source.readBoolean()) return null;
			return super.read(targetObj, cluster, source, oldValue);
		}
		@Override
		public void write(Object targetObj, Cluster cluster, ContentWriter target, T source) throws IOException{
			target.writeBoolean(source==null);
			if(source==null) return;
			super.write(targetObj, cluster, target, source);
		}
		@Override
		public long mapSize(Object targetObj, T source){
			if(source==null) return 1;
			return 1+super.mapSize(targetObj, source);
		}
		@Override
		public OptionalInt getFixedSize(){
			return OptionalInt.empty();
		}
		@Override
		public OptionalInt getMaxSize(){
			return super.getMaxSize().stream().map(s->s+1).findAny();
		}
	}
	
	public static class AutoRW<T extends IOInstance> implements ReaderWriter<T>{
		
		private final IOStruct struct;
		
		public AutoRW(Type targetType){
			struct=IOStruct.getUnknown((Class<?>)targetType);
		}
		
		@Override
		public T read(Object targetObj, Cluster cluster, ContentReader source, T oldValue) throws IOException{
			T val=oldValue==null?struct.newInstance(null):oldValue;
			val.readStruct(cluster, source);
			return val;
		}
		
		@Override
		public void write(Object targetObj, Cluster cluster, ContentWriter target, T source) throws IOException{
			source.writeStruct(cluster, target);
		}
		
		@Override
		public long mapSize(Object targetObj, T source){
			return struct.getKnownSize().orElseGet(source::getInstanceSize);
		}
		
		@Override
		public OptionalInt getFixedSize(){
			var siz=struct.getKnownSize();
			if(siz.isEmpty()) return OptionalInt.empty();
			return OptionalInt.of(Math.toIntExact(siz.getAsLong()));
		}
		
		@Override
		public OptionalInt getMaxSize(){
			var siz=struct.getMaximumSize();
			if(siz.isEmpty()) return OptionalInt.empty();
			return OptionalInt.of(Math.toIntExact(siz.getAsLong()));
		}
	}
	
	private final IOStruct struct;
	protected     boolean  writingInstance;
	
	@NotNull
	public IOStruct getStruct(){ return struct; }
	
	public boolean isWritingInstance(){
		return writingInstance;
	}
	
	public IOInstance(){
		this.struct=IOStruct.get(getClass());
	}
	
	public IOInstance(IOStruct struct){
		Objects.requireNonNull(struct);
		assert getClass()==struct.instanceClass;
		this.struct=struct;
	}
	
	public void readStruct(Cluster cluster, ContentReader in) throws IOException{
		
		boolean shouldClose=false;
		
		ContentReader buff;
		if(ContentReader.isDirect(in)) buff=in;
		else if(getStruct().getKnownSize().isPresent()){
			buff=in.bufferExactRead(getStruct().requireKnownSize());
			shouldClose=true;
		}else if(getStruct().getMinimumSize()<=2) buff=in;
		else buff=new ContentInputStream.Joining2(new ContentInputStream.BA(in.readInts1((int)getStruct().getMinimumSize())), in);
		
		Throwable e1=null;
		try{
			for(var v : getStruct().variableIter){
				read:
				try{
					if(DEBUG_VALIDATION){
						if(v instanceof VariableNode.FixedSize f){
							var siz=f.getSize();
							try(ContentReader vbuf=buff.bufferExactRead(siz, (w, e)->new IOException(this.getClass().getName()+" Var \""+v.info.name()+"\" "+w+"/"+e))){
								v.read(this, cluster, vbuf);
							}
							break read;
						}
					}
					
					v.read(this, cluster, buff);
				}catch(Throwable e){
					throw new IOException("Failed to read variable "+v+" in "+getStruct().toShortString(), e);
				}
			}
		}catch(Throwable e){
			e1=e;
		}finally{
			if(shouldClose){
				try{
					buff.close();
				}catch(Throwable e){
					if(e1!=null) e1.addSuppressed(e);
					else e1=e;
				}
			}
			if(e1!=null){
				throw UtilL.uncheckedThrow(e1);
			}
		}
		
	}
	
	public void writeStruct(Cluster cluster, ContentWriter out) throws IOException{
		boolean       shouldClose=false;
		Throwable     e1         =null;
		ContentWriter buff;
		if(ContentWriter.isDirect(out)) buff=out;
		else{
			buff=out.bufferExactWrite(getInstanceSize());
			shouldClose=true;
		}
		
		try{
			if(writingInstance) throw new ConcurrentModificationException();
			writingInstance=true;
			
			try{
				if(DEBUG_VALIDATION){
					for(var v : getStruct().variableIter){
						var size=v.mapSize(this);
						try(ContentWriter vbuf=buff.bufferExactWrite(size, (written, expected)->new IOException(this.getClass().getName()+"."+v.info.name()+" written/expected "+written+"/"+expected))){
							v.write(this, cluster, vbuf);
						}
					}
				}else{
					for(var v : getStruct().variableIter){
						v.write(this, cluster, buff);
					}
				}
			}catch(Throwable e){
				e1=e;
			}
			
			if(shouldClose){
				try{
					buff.close();
				}catch(Throwable e2){
					if(e1!=null) e1.addSuppressed(e2);
					else throw e2;
				}
			}
			if(e1!=null) throw UtilL.uncheckedThrow(e1);
			
		}finally{
			writingInstance=false;
		}
	}
	
	public long getInstanceSize(){
		var known=getStruct().getKnownSize();
		if(known.isPresent()) return known.getAsLong();
		
		long sum=0;
		for(int i=getStruct().variableIter.length-1;i>=0;i--){
			var v=getStruct().variableIter[i];
			
			sum+=VariableNode.FixedSize.getSizeUnknown(this, v);
			
			Offset off=v.getKnownOffset();
			if(off!=null){
				sum+=off.getOffset();
				break;
			}
		}
		
		return sum;
	}
	
	public void validateWrittenData(Cluster cluster, ContentReader in) throws IOException{
		var siz=getInstanceSize();
		var bb =new ByteArrayOutputStream(Math.toIntExact(siz));
		writeStruct(cluster, SimpleContentWriter.pass(bb));
		
		if(bb.size()!=siz){
			throw new MalformedObjectException("Object declared size of "+siz+" but wrote "+bb.size()+" bytes");
		}
		
		var arr=bb.toByteArray();
		
		byte[] real=new byte[arr.length];
		
		int len=real.length;
		
		int n=0;
		while(n<len){
			int count=in.read(real, n, len-n);
			if(count<0){
				real=Arrays.copyOf(real, n);
				break;
			}
			n+=count;
		}
		
		if(Arrays.equals(arr, real)) return;
		
		if(!DEBUG_VALIDATION) throw new OutOfSyncDataException();
		
		Function<byte[], Map<String, String>> toMap=bytes->IntStream.range(0, bytes.length).boxed().collect(Collectors.toMap(i->""+i, i->String.format("%02X ", bytes[i])));
		throw new OutOfSyncDataException("\n"+TextUtil.toTable(TextUtil.toString(this), List.of(toMap.apply(arr), toMap.apply(real))));
	}
	
	
	protected void initPointerVar(Cluster cluster, int varIndex) throws IOException{
		initPointerVar(cluster, getStruct().getVar(varIndex));
	}
	
	protected void initPointerVarAll(Cluster cluster) throws IOException{
		for(VariableNode<Object> var : getStruct().getVariables()){
			if(var instanceof VariableNode.SelfPointer<?> varPtr){
				initPointerVar(cluster, varPtr);
			}
		}
	}
	
	protected void initPointerVar(Cluster cluster, String varName) throws IOException{
		initPointerVar(cluster, getStruct().getVar(varName));
	}
	
	protected void initPointerVar(Cluster cluster, VariableNode.SelfPointer<?> ptrVar) throws IOException{
		ptrVar.allocNew(this, cluster);
	}
	
	@Override
	public String toString(){
		return variableToString();
	}
	
	public final String variableToString(){
		return getStruct().getVariables().stream()
		                  .map(var->var.toString(this))
		                  .map(e->e.getKey()+": "+TextUtil.toString(e.getValue()))
		                  .collect(joining(", ", getClass().getSimpleName()+"{", "}"));
	}
	
	public Offset calcVarOffset(int index)     { return calcVarOffset(getStruct().getVar(index)); }
	
	public Offset calcVarOffset(String varName){ return calcVarOffset(getStruct().getVar(varName)); }
	
	public Offset calcVarOffset(VariableNode<?> var){
		
		var known=var.getKnownOffset();
		if(known!=null) return known;
		
		ResolvedOffset offset=iterateOffsets((v, off)->v==var);
		
		if(offset==null) throw new IllegalArgumentException(var+" not in "+getStruct());
		
		return offset.offset;
	}
	
	public static record ResolvedOffset(VariableNode<?> var, Offset offset){}
	
	public void iterateOffsets(BiConsumer<VariableNode<?>, Offset> consumer){
		iterateOffsets((v, o)->{
			consumer.accept(v, o);
			return false;
		});
	}
	
	public ResolvedOffset iterateOffsets(BiPredicate<VariableNode<?>, Offset> consumer){
		Offset offset=Offset.ZERO;
		
		for(VariableNode<?> node : getStruct().variableIter){
			if(node instanceof BitBlockNode block){
				Offset blockOffset=offset.addBits(0);
				for(VariableNode.Flag<?> flag : block.flagsNodes){
					if(consumer.test(flag, blockOffset)) return new ResolvedOffset(flag, blockOffset);
					blockOffset=blockOffset.addBits(flag.getTotalBits());
				}
				
				var pred=offset.addBytes(block.blockInfo().wordSize().bytes);
				assert blockOffset.equals(pred):blockOffset+" "+pred;
			}else{
				if(consumer.test(node, offset)) return new ResolvedOffset(node, offset);
			}
			
			offset=offset.addBytes(VariableNode.FixedSize.getSizeUnknown(this, node));
		}
		
		return null;
	}
}
