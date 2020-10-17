package com.lapissea.cfs.io.struct;

import com.lapissea.cfs.cluster.Cluster;
import com.lapissea.cfs.exceptions.MalformedObjectException;
import com.lapissea.cfs.exceptions.OutOfSyncDataException;
import com.lapissea.cfs.io.RandomIO;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.lapissea.cfs.Config.*;
import static java.util.stream.Collectors.*;

public class IOInstance{
	
	public abstract static class Contained extends IOInstance{
		
		public abstract static class SingletonChunk<SELF extends SingletonChunk<SELF>> extends Contained implements SelfPoint<SELF>{
			
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
			public ObjectPointer<SELF> getSelfPtr(){
				return new ObjectPointer.Struct<>(getContainer().getPtr(), 0, getSelfConstructor());
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
				buff.ensureCapacity(buff.getPos()+this.getInstanceSize());
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
	
	private final IOStruct struct;
	
	@NotNull
	public IOStruct getStruct(){ return struct; }
	
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
							try(ContentReader vbuf=buff.bufferExactRead(siz, (w, e)->new IOException(this.getClass().getName()+" Var \""+v.name+"\" "+w+"/"+e))){
								v.read(this, cluster, vbuf);
							}
							break read;
						}
					}
					
					v.read(this, cluster, buff);
				}catch(IOException e){
					throw new IOException("Failed to read variable "+v+" in "+getStruct()+" from "+buff, e);
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
			if(DEBUG_VALIDATION){
				for(var v : getStruct().variableIter){
					var size=v.mapSize(this);
					try(ContentWriter vbuf=buff.bufferExactWrite(size, (written, expected)->new IOException(this.getClass().getName()+" Var \""+v.name+"\" written/expected "+written+"/"+expected))){
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
	
	
	@Override
	public String toString(){
		return variableToString();
	}
	
	public final String variableToString(){
		return getStruct().variables.stream()
		                            .map(var->var.toString(this))
		                            .map(e->e.getKey()+": "+TextUtil.toString(e.getValue()))
		                            .collect(joining(", ", getClass().getSimpleName()+"{", "}"));
	}

//	public String toShortString(){
//		return getClass().getSimpleName()+toTableString();
//	}
//	public String toTableString(){
//		return getStruct().variables.stream().map(var->var.toString(this))
//		                            .filter(e->{
//			                            if(e.getValue() instanceof Boolean b) return b;
//			                            if(e.getValue() instanceof Number n) return n.longValue()!=0;
//			                            if(e.getValue() instanceof INumber n) return n.getValue()!=0;
//			                            return e.getValue()!=null;
//		                            })
//		                            .map(e->{
//			                            if(e.getValue() instanceof Boolean b) return e.getKey();
//			                            return e.getKey()+": "+TextUtil.toString(e.getValue());
//		                            })
//		                            .collect(joining(", ", "{", "}"));
//	}
	
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
