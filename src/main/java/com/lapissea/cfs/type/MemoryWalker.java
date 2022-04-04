package com.lapissea.cfs.type;

import com.lapissea.cfs.chunk.Chunk;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.annotations.IOType;
import com.lapissea.cfs.type.field.fields.reflection.IOFieldPrimitive;
import com.lapissea.util.LogUtil;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Objects;
import java.util.stream.Stream;

import static com.lapissea.cfs.GlobalConfig.DEBUG_VALIDATION;
import static com.lapissea.cfs.type.field.VirtualFieldDefinition.StoragePool.IO;

public class MemoryWalker{
	
	public record IterationOptions(boolean shouldContinue, boolean shouldSave){
		public static final IterationOptions CONTINUE_NO_SAVE =new IterationOptions(true, false);
		public static final IterationOptions CONTINUE_AND_SAVE=new IterationOptions(true, true);
		public static final IterationOptions END              =new IterationOptions(false, false);
		public static final IterationOptions SAVE_AND_END     =new IterationOptions(false, true);
		
	}
	
	public interface PointerRecord{
		/**
		 * @return if walking should continue
		 */
		<T extends IOInstance<T>> IterationOptions log(StructPipe<T> pipe, Reference instanceReference, IOField.Ref<T, ?> field, T instance, Reference value) throws IOException;
		/**
		 * @return if walking should continue
		 */
		<T extends IOInstance<T>> IterationOptions logChunkPointer(StructPipe<T> pipe, Reference instanceReference, IOField<T, ChunkPointer> field, T instance, ChunkPointer value) throws IOException;
	}
	
	private final DataProvider cluster;
	private final IOInstance   root;
	private final Reference    rootReference;
	private final StructPipe   pipe;
	
	public <T extends IOInstance.Unmanaged<T>> MemoryWalker(T root){
		this(root.getDataProvider(), root, root.getReference(), root.getPipe());
	}
	public <T extends IOInstance<T>> MemoryWalker(DataProvider cluster, T root, Reference rootReference, StructPipe<T> pipe){
		this.cluster=cluster;
		this.root=root;
		this.rootReference=rootReference;
		this.pipe=pipe;
	}
	
	public <T extends IOInstance<T>> void walk(boolean self, UnsafeConsumer<Reference, IOException> consumer) throws IOException{
		if(self) consumer.accept(rootReference);
		walkStructFull(cluster, (T)root, rootReference, (StructPipe<T>)pipe, new PointerRecord(){
			@Override
			public <I extends IOInstance<I>> IterationOptions log(StructPipe<I> pipe, Reference instanceReference, IOField.Ref<I, ?> field, I instance, Reference value) throws IOException{
				consumer.accept(value);
				return IterationOptions.CONTINUE_NO_SAVE;
			}
			@Override
			public <I extends IOInstance<I>> IterationOptions logChunkPointer(StructPipe<I> pipe, Reference instanceReference, IOField<I, ChunkPointer> field, I instance, ChunkPointer value) throws IOException{
				consumer.accept(value.makeReference());
				return IterationOptions.CONTINUE_NO_SAVE;
			}
		}, false);
	}
	
	public <T extends IOInstance<T>> void walk(PointerRecord consumer) throws IOException{
		walkStructFull(cluster, (T)root, rootReference, (StructPipe<T>)pipe, consumer, false);
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
	
	private <T extends IOInstance<T>> IterationOptions walkStructFull(DataProvider cluster,
	                                                                  T instance, Reference instanceReference, StructPipe<T> pipe,
	                                                                  PointerRecord pointerRecord, boolean inlinedParent) throws IOException{
		return walkStructFull(cluster, new RefStack(), instance, instanceReference, pipe, pointerRecord, inlinedParent);
	}
	
	@SuppressWarnings("unchecked")
	private <T extends IOInstance<T>> IterationOptions walkStructFull(DataProvider cluster, RefStack stack,
	                                                                  T instance, Reference instanceReference, StructPipe<T> pipe,
	                                                                  PointerRecord pointerRecord, boolean inlinedParent) throws IOException{
		if(instance==null){
			return IterationOptions.CONTINUE_NO_SAVE;
		}
		if(!instance.getThisStruct().getCanHavePointers()){
			return IterationOptions.CONTINUE_NO_SAVE;
		}
		
		var reference=instanceReference;
		if(instance instanceof Chunk c){
			var off=cluster.getFirstChunk().getPtr();
			reference=new Reference(off, c.getPtr().getValue()-off.getValue());
		}
		if(reference==null||reference.isNull()) return IterationOptions.CONTINUE_NO_SAVE;
		boolean inlineDirtyButContinue=false;
		if(stack.contains(reference, instance)){
			return IterationOptions.CONTINUE_NO_SAVE;
		}
		stack.push(reference, instance);
		try{
			
			
			var fieldOffset=0L;
			
			Iterator<IOField<T, ?>> iterator;
			if(instance instanceof IOInstance.Unmanaged unmanaged){
				iterator=Stream.concat(pipe.getSpecificFields().stream(), unmanaged.listUnmanagedFields()).iterator();
			}else{
				iterator=pipe.getSpecificFields().iterator();
			}
			var ioPool=instance.getThisStruct().allocVirtualVarPool(IO);
			while(iterator.hasNext()){
				IOField<T, ?> field=iterator.next();
				
				final long size;
				var        sizeDesc=field.getSizeDescriptor();
				try{
					size=sizeDesc.calcUnknown(ioPool, cluster, instance);
				}catch(Throwable e){
					throw e;
				}
				
				try{
					if(field.getAccessor()==null){
						continue;
					}
					
					if(UtilL.checkFlag(field.typeFlags(), IOField.PRIMITIVE_OR_ENUM_FLAG)){
						continue;
					}
					if(UtilL.checkFlag(field.typeFlags(), IOField.HAS_NO_POINTERS_FLAG)){
						continue;
					}
					
					Class<?> type=field.getAccessor().getType();
					
					if(type.isArray()){
						var pType=type;
						while(pType.isArray()){
							pType=pType.componentType();
						}
						
						if(IOFieldPrimitive.isPrimitive(pType)||pType.isEnum()||pType==String.class){
							continue;
						}
					}
					
					var dynamic   =UtilL.checkFlag(field.typeFlags(), IOField.DYNAMIC_FLAG);
					var isInstance=UtilL.checkFlag(field.typeFlags(), IOField.IOINSTANCE_FLAG);
					
					if(dynamic){
						var inst=field.get(ioPool, instance);
						if(inst==null) continue;
						type=inst.getClass();
						
						if(isInstance&&inst instanceof IOInstance.Unmanaged valueInstance){
							var res=walkStructFull(cluster, stack, valueInstance, valueInstance.getReference(), valueInstance.getPipe(), pointerRecord, false);
							if(res.shouldSave){
								throw new NotImplementedException();//TODO
							}
							if(!res.shouldContinue) return IterationOptions.END;
							continue;
						}
					}
					
					if(field instanceof IOField.Ref<?, ?> refO){
						IOField.Ref<T, T> refField=(IOField.Ref<T, T>)refO;
						var               ref     =refField.getReference(instance);
						
						{
							var res=pointerRecord.log(pipe, reference, refField, instance, ref);
							checkResult(res);
							if(res.shouldSave&&res.shouldContinue&&inlinedParent){
								inlineDirtyButContinue=true;
							}else{
								if(res.shouldSave){
									if(inlinedParent){
										return IterationOptions.SAVE_AND_END;
									}
									
									try(var io=reference.io(cluster)){
										pipe.write(cluster, io, instance);
									}
								}
								if(!res.shouldContinue) return IterationOptions.END;
							}
						}
						{
							if(!dynamic){
								if(isInstance){
									var typ=refField.getAccessor().getType();
									if(!Struct.of((Class)typ).getCanHavePointers()){
										continue;
									}
								}
							}
							var res=walkStructFull(cluster, stack, refField.get(ioPool, instance), ref, refField.getReferencedPipe(instance), pointerRecord, false);
							if(res.shouldSave){
								throw new NotImplementedException();//TODO
							}
							if(!res.shouldContinue) return IterationOptions.END;
						}
					}else if(UtilL.instanceOf(type, ChunkPointer.class)){
						var ptrField=(IOField<T, ChunkPointer>)field;
						
						var ch=ptrField.get(ioPool, instance);
						
						if(!ch.isNull()){
							{
								var res=pointerRecord.logChunkPointer(pipe, reference, ptrField, instance, ch);
								checkResult(res);
								if(res.shouldSave){
									throw new NotImplementedException();//TODO
								}
								if(!res.shouldContinue) return IterationOptions.END;
							}
							{
								var res=walkStructFull(cluster, stack, ch.dereference(cluster), null, Chunk.PIPE, pointerRecord, false);
								if(res.shouldSave){
									throw new NotImplementedException();//TODO
								}
								if(!res.shouldContinue) return IterationOptions.END;
							}
						}
					}else{
						var typ=type;
						if(typ.isArray()){
							var component=typ.componentType();
							if(UtilL.instanceOf(component, IOInstance.class)){
								if(!Struct.of((Class)typ).getCanHavePointers()){
									continue;
								}
								var array=(IOInstance<?>[])field.get(ioPool, instance);
								if(array==null||array.length==0) continue;
								var pip=StructPipe.of(pipe.getClass(), array[0].getThisStruct());
								for(IOInstance<?> inst : array){
									{
										var res=walkStructFull(cluster, stack, (T)inst, reference.addOffset(fieldOffset), pip, pointerRecord, true);
										if(res.shouldSave){
											throw new NotImplementedException();//TODO
										}
										if(!res.shouldContinue) return IterationOptions.END;
									}
									fieldOffset+=pip.calcUnknownSize(cluster, inst, WordSpace.BYTE);
								}
								continue;
							}
							if(IOFieldPrimitive.isPrimitive(component)){
								continue;
							}
							if(component==String.class){
								continue;
							}
						}
						if(isInstance){
							var inst=(IOInstance)field.get(ioPool, instance);
							if(inst!=null){
								{
									var res=walkStructFull(cluster, stack, (T)inst, reference.addOffset(fieldOffset), StructPipe.of(pipe.getClass(), inst.getThisStruct()), pointerRecord, true);
									if(res.shouldSave&&res.shouldContinue&&inlinedParent){
										inlineDirtyButContinue=true;
									}else{
										if(res.shouldSave){
											if(inlinedParent) return IterationOptions.SAVE_AND_END;
											if(instance instanceof IOInstance.Unmanaged<?>){
												((IOField)field).set(ioPool, instance, inst);
											}else{
												try(var io=reference.io(cluster)){
													pipe.write(cluster, io, instance);
												}
											}
										}
										
										if(!res.shouldContinue) return IterationOptions.END;
									}
								}
							}
							continue;
						}
						if(typ==String.class){
							continue;
						}
						if(field.getAccessor().hasAnnotation(IOType.Dynamic.class)){
							var inst=field.get(ioPool, instance);
							if(inst==null) continue;
							
							if(inst instanceof IOInstance i){
								if(!i.getThisStruct().getCanHavePointers()) continue;
								{
									var res=walkStructFull(cluster, stack, i, reference.addOffset(fieldOffset), StructPipe.of(pipe.getClass(), i.getThisStruct()), pointerRecord, true);
									if(res.shouldSave){
										throw new NotImplementedException();//TODO
									}
									if(!res.shouldContinue) return IterationOptions.END;
								}
								continue;
							}
							
							continue;
						}
						
						throw new RuntimeException(TextUtil.toString("unmanaged walk type:", typ.toString(), field.getAccessor()));
					}
				}catch(Throwable e){
					String instStr=instanceErrStr(instance);
					throw new RuntimeException("failed to walk on "+field+" in "+instStr, e);
				}finally{
					fieldOffset+=field.getSizeDescriptor().mapSize(WordSpace.BYTE, size);
				}
			}
		}finally{
			stack.pop();
		}
		if(inlineDirtyButContinue){
			return IterationOptions.CONTINUE_AND_SAVE;
		}
		return IterationOptions.CONTINUE_NO_SAVE;
	}
	
	private void checkResult(IterationOptions res){
		if(cluster.isReadOnly()){
			if(res.shouldSave()){
				throw new IllegalStateException("Tried to save on read only walk");
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
