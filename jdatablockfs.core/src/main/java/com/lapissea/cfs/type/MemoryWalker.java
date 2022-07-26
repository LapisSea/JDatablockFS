package com.lapissea.cfs.type;

import com.lapissea.cfs.chunk.Chunk;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.util.LogUtil;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.IOException;
import java.util.*;
import java.util.function.DoubleConsumer;
import java.util.stream.Stream;

import static com.lapissea.cfs.GlobalConfig.DEBUG_VALIDATION;
import static com.lapissea.cfs.type.CommandSet.*;
import static com.lapissea.cfs.type.field.VirtualFieldDefinition.StoragePool.IO;

public class MemoryWalker{
	
	public static class AverageDouble implements DoubleConsumer{
		private int    n     =1;
		private double curAvg=0;
		
		@Override
		public void accept(double newNum){
			curAvg=curAvg+(newNum-curAvg)/n;
			n++;
		}
		
		public double getAvg(){
			return curAvg;
		}
		public double getTotal(){
			return curAvg*n;
		}
		public int getCount(){
			return n;
		}
	}
	
	public record Stat(AverageDouble localTime, AverageDouble iterNext){
		public Stat(){
			this(new AverageDouble(), new AverageDouble());
		}
	}
	
	
	private static final int DATA_MASK=0b00001;
	public static final  int SAVE     =0b00001;
	/**/
	private static final int FLOW_MASK=0b00110;
	public static final  int CONTINUE =0b00010;
	public static final  int END      =0b00100;
	public static final  int REPEAT   =0b00110;
	
	private static final int INTERNAL_MASK=0b1000000000000000000000000000000;
	private static final int NO_RESULT    =0b1000000000000000000000000000000;
	
	
	private static int data(int flags)          {return flags&FLOW_MASK;}
	private static boolean fSave(int flags)     {return UtilL.checkFlag(flags&DATA_MASK, SAVE);}
	private static boolean hasResult(int result){return !UtilL.checkFlag(result, NO_RESULT);}
	
	public interface PointerRecord{
		/**
		 * @return if walking should continue
		 */
		<T extends IOInstance<T>> int log(Reference instanceReference, T instance, IOField.Ref<T, ?> field, Reference valueReference) throws IOException;
		/**
		 * @return if walking should continue
		 */
		<T extends IOInstance<T>> int logChunkPointer(Reference instanceReference, T instance, IOField<T, ChunkPointer> field, ChunkPointer value) throws IOException;
	}
	
	private final DataProvider provider;
	private final IOInstance   root;
	private final Reference    rootReference;
	private final StructPipe   pipe;
	
	private final Map<Class<?>, Stat> stats=new HashMap<>();
	private       boolean             record;
	
	public <T extends IOInstance.Unmanaged<T>> MemoryWalker(T root){
		this(root.getDataProvider(), root, root.getReference(), root.getPipe());
	}
	public <T extends IOInstance<T>> MemoryWalker(DataProvider provider, T root, Reference rootReference, StructPipe<T> pipe){
		this.provider=provider;
		this.root=root;
		this.rootReference=rootReference;
		this.pipe=pipe;
	}
	
	public void recordInfo(){
		record=true;
	}
	
	public Map<Class<?>, Stat> getStats(){
		return stats;
	}
	
	@SuppressWarnings({"RedundantCast", "unchecked"})
	public <T extends IOInstance<T>> void walk(boolean self, UnsafeConsumer<Reference, IOException> consumer) throws IOException{
		if(self) consumer.accept(rootReference);
		walkStructFull((T)root, rootReference, (StructPipe<T>)pipe, new PointerRecord(){
			@Override
			public <I extends IOInstance<I>> int log(Reference instanceReference, I instance, IOField.Ref<I, ?> field, Reference valueReference) throws IOException{
				consumer.accept(valueReference);
				return CONTINUE;
			}
			@Override
			public <I extends IOInstance<I>> int logChunkPointer(Reference instanceReference, I instance, IOField<I, ChunkPointer> field, ChunkPointer value) throws IOException{
				consumer.accept(value.makeReference());
				return CONTINUE;
			}
		});
	}
	
	@SuppressWarnings({"RedundantCast", "unchecked"})
	public <T extends IOInstance<T>> int walk(PointerRecord consumer) throws IOException{
		return walkStructFull((T)root, rootReference, (StructPipe<T>)pipe, consumer);
	}
	
	private static class RefStack{
		
		private IOInstance<?>[] oBuff=new IOInstance[16];
		private long[]          buff =new long[32];
		private int             siz;
		
		<T extends IOInstance<T>> boolean contains(Reference ref, T inst){
			if(siz==0) return false;
			var ptr=ref.getPtr().getValue();
			var off=ref.getOffset();
			for(int i=0;i<siz;i++){
				if(buff[i*2]==ptr&&buff[i*2+1]==off){
					var o=oBuff[siz];
					if(!Objects.equals(inst, o)) continue;
					
					if(DEBUG_VALIDATION){
						if(
							o instanceof IOInstance.Unmanaged<?> u1&&inst instanceof IOInstance.Unmanaged<?> u2&&(
								!u1.getReference().equals(u2.getReference())||
								!u1.getTypeDef().equals(u2.getTypeDef())
							)||
							!inst.toString().equals(o.toString())
						){
							LogUtil.printlnEr("Possible equality problem?\n"+inst+"\n"+o);
						}
					}
					
					return true;
				}
			}
			return false;
		}
		
		<T extends IOInstance<T>> void push(Reference ref, T inst){
			if(siz==oBuff.length){
				buff=Arrays.copyOf(buff, buff.length*2);
				oBuff=Arrays.copyOf(oBuff, oBuff.length*2);
			}
			buff[siz*2]=ref.getPtr().getValue();
			buff[siz*2+1]=ref.getOffset();
			oBuff[siz]=inst;
			siz++;
		}
		void pop(){
			if(siz==0) throw new IllegalStateException();
			siz--;
			oBuff[siz]=null;
		}
	}
	
	private <T extends IOInstance<T>> int walkStructFull(
		T instance, Reference instanceReference, StructPipe<T> pipe,
		PointerRecord pointerRecord
	) throws IOException{
		var stack=new RefStack();
		return walkStructFull(stack, instance, instanceReference, pipe, pointerRecord, false);
	}
	
	@SuppressWarnings({"unchecked", "rawtypes"})
	private <T extends IOInstance<T>> int walkStructFull(
		RefStack stack,
		T instance, Reference instanceReference, StructPipe<T> pipe,
		PointerRecord pointerRecord, boolean inlinedParent
	) throws IOException{
		if(instance==null){
			return CONTINUE;
		}
		var instanceStruct=instance.getThisStruct();
		if(!instanceStruct.getCanHavePointers()){
			return CONTINUE;
		}
		
		var reference=instanceReference;
		if(instance instanceof Chunk c){
			var off=provider.getFirstChunk().getPtr();
			reference=new Reference(off, c.getPtr().getValue()-off.getValue());
		}
		if(reference==null||reference.isNull()) return CONTINUE;
		boolean inlineDirtyButContinue=false;
		if(stack.contains(reference, instance)){
			return CONTINUE;
		}
		
		
		stack.push(reference, instance);
		long t0=0, iterNextSum=0;
		try{
			if(record){
				t0=System.nanoTime();
			}
			
			var fieldOffset=0L;
			
			long in0=0;
			if(record) in0=System.nanoTime();
			Iterator<IOField<T, ?>> iterator;
			if(instance instanceof IOInstance.Unmanaged unmanaged){
				iterator=Stream.concat(pipe.getSpecificFields().stream(), unmanaged.listUnmanagedFields()).iterator();
			}else{
				iterator=pipe.getSpecificFields().iterator();
			}
			if(record){
				iterNextSum+=(System.nanoTime()-in0);
			}
			var cmds         =pipe.getReferenceWalkCommands().reader();
			var unmanagedCmds=false;
			var ioPool       =instanceStruct.allocVirtualVarPool(IO);
			wh:
			while(true){
				if(record) in0=System.nanoTime();
				if(!iterator.hasNext()) break;
				
				IOField<T, ?> field=iterator.next();
				
				if(record){
					iterNextSum+=(System.nanoTime()-in0);
				}
				if(!unmanagedCmds){
					switch(cmds.cmd()){
						case ENDF -> {break wh;}
						case UNMANAGED_REST -> unmanagedCmds=true;
						case SKIPB_B -> {
							var siz=cmds.read8();
							fieldOffset+=siz;
							continue;
						}
						case SKIPB_I -> {
							var siz=cmds.read32();
							fieldOffset+=siz;
							continue;
						}
						case SKIPB_L -> {
							var siz=cmds.read64();
							fieldOffset+=siz;
							continue;
						}
						case SKIPB_UNKOWN -> {
							var  sizeDesc=field.getSizeDescriptor();
							long size    =sizeDesc.calcUnknown(ioPool, provider, instance, WordSpace.BYTE);
							fieldOffset+=field.getSizeDescriptor().mapSize(WordSpace.BYTE, size);
							continue;
						}
						case SKIPF_N_IF_NULL -> {
							LogUtil.println("SKIPF_N_IF_NULL");
							throw new NotImplementedException();
						}
						case POTENTIAL_REF -> {}
						default -> throw new NotImplementedException();
					}
				}
				
				var  sizeDesc=field.getSizeDescriptor();
				long size    =sizeDesc.calcUnknown(ioPool, provider, instance, WordSpace.BYTE);
				
				try{
					var accessor=field.getAccessor();
					if(accessor==null) continue;
					
					Class<?> type=accessor.getType();
					
					var dynamic   =field.typeFlag(IOField.DYNAMIC_FLAG);
					var isInstance=field.typeFlag(IOField.IOINSTANCE_FLAG);
					
					if(dynamic){
						var inst=field.get(ioPool, instance);
						if(inst==null) continue;
						type=inst.getClass();
						
						if(isInstance&&inst instanceof IOInstance.Unmanaged valueInstance){
							long t0v=record?System.nanoTime():0;
							var  res=walkStructFull(stack, valueInstance, valueInstance.getReference(), valueInstance.getPipe(), pointerRecord, false);
							if(record){
								var t1v =System.nanoTime();
								var diff=t1v-t0v;
								t0+=diff;
							}
							if(fSave(res)){
								throw new NotImplementedException();//TODO
							}
							switch(res&FLOW_MASK){
								case CONTINUE -> {
									continue;
								}
								case END -> {return END;}
								case REPEAT -> throw new NotImplementedException();
								default -> throw new NotImplementedException((res&FLOW_MASK)+"");
							}
						}
					}
					
					if(field instanceof IOField.Ref<?, ?> refO){
						IOField.Ref<T, T> refField=(IOField.Ref<T, T>)refO;
						var               ref     =refField.getReference(instance);
						
						if(ref.isNull()) continue;
						
						{
							long t0v=record?System.nanoTime():0;
							var  res=pointerRecord.log(reference, instance, refField, ref);
							if(record){
								var t1v =System.nanoTime();
								var diff=t1v-t0v;
								t0+=diff;
							}
							checkResult(res);
							if(fSave(res)&&data(res)==CONTINUE&&inlinedParent&&field.getSizeDescriptor().hasFixed()){
								inlineDirtyButContinue=true;
							}else{
								if(fSave(res)){
									if(inlinedParent){
										return SAVE|END;
									}
									
									try(var io=reference.io(provider)){
										pipe.write(provider, io, instance);
									}
								}
								switch(data(res)){
									case CONTINUE -> {}
									case END -> {return END;}
									case REPEAT -> throw new NotImplementedException();
									default -> throw new NotImplementedException(data(res)+"");
								}
							}
						}
						{
							if(!isInstance){
								continue;
							}
							if(!dynamic){
								var typ=refField.getAccessor().getType();
								if(!Struct.ofUnknown(typ).getCanHavePointers()){
									continue;
								}
							}
							
							var instRefField=(IOField.Ref<T, T> & IOField.Ref.Inst<T, T>)refField;
							
							long t0v=record?System.nanoTime():0;
							var  res=walkStructFull(stack, instRefField.get(ioPool, instance), ref, instRefField.getReferencedPipe(instance), pointerRecord, false);
							if(record){
								var t1v =System.nanoTime();
								var diff=t1v-t0v;
								t0+=diff;
							}
							if(fSave(res)){
								throw new NotImplementedException();//TODO
							}
							switch(data(res)){
								case CONTINUE -> {}
								case END -> {return END;}
								case REPEAT -> throw new NotImplementedException();
								default -> throw new NotImplementedException(data(res)+"");
							}
						}
					}else if(UtilL.instanceOf(type, ChunkPointer.class)){
						var result=handlePtr(stack, instance, pipe, pointerRecord, reference, ioPool, (IOField<T, ChunkPointer>)field);
						if(hasResult(result)) return result;
					}else{
						if(type.isArray()){
							var component=type.componentType();
							if(IOInstance.isInstance(component)){
								if(!Struct.ofUnknown(component).getCanHavePointers()){
									continue;
								}
								var array=(IOInstance<?>[])field.get(ioPool, instance);
								if(array==null||array.length==0) continue;
								var pip=StructPipe.of(pipe.getClass(), array[0].getThisStruct());
								for(IOInstance<?> inst : array){
									{
										long t0v=record?System.nanoTime():0;
										var  res=walkStructFull(stack, (T)inst, reference.addOffset(fieldOffset), pip, pointerRecord, true);
										if(record){
											var t1v =System.nanoTime();
											var diff=t1v-t0v;
											t0+=diff;
										}
										if(fSave(res)){
											throw new NotImplementedException();//TODO
										}
										switch(data(res)){
											case CONTINUE -> {}
											case END -> {return END;}
											case REPEAT -> throw new NotImplementedException();
											default -> throw new NotImplementedException(data(res)+"");
										}
									}
									fieldOffset+=pip.calcUnknownSize(provider, inst, WordSpace.BYTE);
								}
								continue;
							}
							if(SupportedPrimitive.isAny(component)){
								continue;
							}
							if(component==String.class){
								continue;
							}
						}
						if(isInstance){
							var fieldValue=(IOInstance<?>)field.get(ioPool, instance);
							if(fieldValue!=null){
								{
									long t0v=record?System.nanoTime():0;
									var  res=walkStructFull(stack, (T)fieldValue, reference.addOffset(fieldOffset), StructPipe.of(pipe.getClass(), fieldValue.getThisStruct()), pointerRecord, true);
									if(record){
										var t1v =System.nanoTime();
										var diff=t1v-t0v;
										t0+=diff;
									}
									if(fSave(res)&&data(res)==CONTINUE&&inlinedParent){
										inlineDirtyButContinue=true;
									}else{
										var result=handleResult(ioPool, instance, pipe, inlinedParent, reference, (IOField<T, IOInstance<?>>)field, fieldValue, res);
										if(hasResult(result)) return result;
									}
								}
							}
							continue;
						}
						if(SupportedPrimitive.isAny(type)){
							continue;
						}
						if(type==String.class){
							continue;
						}
						if(dynamic){
							var fieldValue=field.get(ioPool, instance);
							if(fieldValue==null) continue;
							
							if(fieldValue instanceof IOInstance fieldValueInstance){
								if(!fieldValueInstance.getThisStruct().getCanHavePointers()) continue;
								{
									long t0v=record?System.nanoTime():0;
									var  res=walkStructFull(stack, fieldValueInstance, reference.addOffset(fieldOffset), StructPipe.of(pipe.getClass(), fieldValueInstance.getThisStruct()), pointerRecord, true);
									if(record){
										var t1v =System.nanoTime();
										var diff=t1v-t0v;
										t0+=diff;
									}
									
									var result=handleResult(ioPool, instance, pipe, inlinedParent, reference, (IOField<T, Object>)field, fieldValue, res);
									if(hasResult(result)) return result;
									
								}
								continue;
							}
							
							continue;
						}
						
						throw new RuntimeException(TextUtil.toString("unmanaged walk type:", type.toString(), field.getAccessor()));
					}
				}catch(Throwable e){
					String instStr=instanceErrStr(instance);
					throw new RuntimeException("failed to walk on "+field+" in "+instStr, e);
				}finally{
					fieldOffset+=field.getSizeDescriptor().mapSize(WordSpace.BYTE, size);
				}
			}
		}finally{
			if(record){
				var t1  =System.nanoTime();
				var diff=(t1-t0)/1000_000D;
				var info=stats.computeIfAbsent(instance.getClass(), c->new Stat());
				info.localTime().accept(diff);
				info.iterNext().accept(iterNextSum/1000_000D);
			}
			stack.pop();
		}
		if(inlineDirtyButContinue){
			return CONTINUE|SAVE;
		}
		return CONTINUE;
	}
	
	private <T extends IOInstance<T>> int handlePtr(RefStack stack, T instance, StructPipe<T> pipe, PointerRecord pointerRecord, Reference reference, Struct.Pool<T> ioPool, IOField<T, ChunkPointer> ptrField) throws IOException{
		var ch=ptrField.get(ioPool, instance);
		
		if(!ch.isNull()){
			{
				var res=pointerRecord.logChunkPointer(reference, instance, ptrField, ch);
				checkResult(res);
				if(fSave(res)){
					throw new NotImplementedException();//TODO
				}
				switch(data(res)){
					case CONTINUE -> {}
					case END -> {return END;}
					case REPEAT -> throw new NotImplementedException();
					default -> throw new NotImplementedException(data(res)+"");
				}
			}
			{
				var res=walkStructFull(stack, ch.dereference(provider), null, Chunk.PIPE, pointerRecord, false);
				if(fSave(res)){
					throw new NotImplementedException();//TODO
				}
				switch(data(res)){
					case CONTINUE -> {}
					case END -> {return END;}
					case REPEAT -> throw new NotImplementedException();
					default -> throw new NotImplementedException(data(res)+"");
				}
			}
		}
		return NO_RESULT;
	}
	
	private <T extends IOInstance<T>, FT> int handleResult(
		Struct.Pool<T> ioPool, T instance, StructPipe<T> pipe,
		boolean inlinedParent, Reference instanceReference,
		IOField<T, FT> field, FT fieldValue,
		int res
	) throws IOException{
		if(fSave(res)){
			if(inlinedParent){
				return SAVE|END;
			}
			if(instance instanceof IOInstance.Unmanaged<?>){
				field.set(ioPool, instance, fieldValue);
			}else{
				try(var io=instanceReference.io(provider)){
					pipe.write(provider, io, instance);
				}
			}
		}
		
		switch(data(res)){
			case CONTINUE -> {return NO_RESULT;}
			case END -> {return END;}
			case REPEAT -> throw new NotImplementedException();
			default -> throw new NotImplementedException(data(res)+"");
		}
	}
	
	private void checkResult(int res){
		if(provider.isReadOnly()){
			if(fSave(res)){
				throw new IllegalStateException("Tried to save on read only walk");
			}
		}
		
		if(DEBUG_VALIDATION){
			var data=res&FLOW_MASK;
			if(!UtilL.contains(new int[]{CONTINUE, END, REPEAT}, data)){
				throw new IllegalStateException("no flow flag provided");
			}
		}
	}
	
	private <T extends IOInstance<T>> String instanceErrStr(T instance){
		String instStr;
		try{
			instStr=instance.toString();
		}catch(Throwable e1){
			instStr="<err toString "+e1.getMessage()+" for "+instance.getClass().getName()+">";
		}
		return instStr;
	}
	
	public IOInstance getRoot(){
		return root;
	}
}
