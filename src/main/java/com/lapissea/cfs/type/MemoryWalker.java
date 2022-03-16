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
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
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
		walkStructFull(cluster, new LinkedList<>(), (T)root, rootReference, (StructPipe<T>)pipe, new PointerRecord(){
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
		walkStructFull(cluster, new LinkedList<>(), (T)root, rootReference, (StructPipe<T>)pipe, consumer, false);
	}
	
	@SuppressWarnings("unchecked")
	private <T extends IOInstance<T>> IterationOptions walkStructFull(DataProvider cluster, List<IOInstance<?>> stack,
	                                                                  T instance, Reference instanceReference, StructPipe<T> pipe,
	                                                                  PointerRecord pointerRecord, boolean inlinedParent) throws IOException{
		var reference=instanceReference;
		if(instance instanceof Chunk c){
			var off=cluster.getFirstChunk().getPtr();
			reference=new Reference(off, c.getPtr().getValue()-off.getValue());
		}
		if(reference==null||reference.isNull()) return IterationOptions.CONTINUE_NO_SAVE;
		boolean inlineDirtyButContinue=false;
		try{
			if(stack.contains(instance)){
				if(DEBUG_VALIDATION){
					for(IOInstance<?> ioInstance : stack){
						if(ioInstance.equals(instance)){
							
							if(
								ioInstance instanceof IOInstance.Unmanaged<?> u1&&instance instanceof IOInstance.Unmanaged<?> u2&&(
									!u1.getReference().equals(u2.getReference())||
									!u1.getTypeDef().equals(u2.getTypeDef())
								)||
								!ioInstance.toString().equals(instance.toString())
							){
								LogUtil.printlnEr("Possible equality problem?\n"+ioInstance+"\n"+instance);
							}
							break;
						}
					}
				}
				return IterationOptions.CONTINUE_NO_SAVE;
			}
			stack.add(instance);
			
			var fieldOffset=0L;
			
			Iterator<IOField<T, ?>> iterator;
			if(instance instanceof IOInstance.Unmanaged unmanaged){
				iterator=Stream.concat(pipe.getSpecificFields().stream(), unmanaged.listDynamicUnmanagedFields()).iterator();
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
					
					Class<?> type=field.getAccessor().getType();
					
					if(field.getAccessor().hasAnnotation(IOType.Dynamic.class)){
						var inst=field.get(ioPool, instance);
						if(inst==null) continue;
						type=inst.getClass();
						
						if(inst instanceof IOInstance.Unmanaged valueInstance){
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
							var res=pointerRecord.log(pipe, instanceReference, refField, instance, ref);
							checkResult(res);
							if(res.shouldSave&&res.shouldContinue&&inlinedParent){
								inlineDirtyButContinue=true;
							}else{
								if(res.shouldSave){
									if(inlinedParent){
										return IterationOptions.SAVE_AND_END;
									}
									
									try(var io=instanceReference.io(cluster)){
										pipe.write(cluster, io, instance);
									}
								}
								if(!res.shouldContinue) return IterationOptions.END;
							}
						}
						{
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
								var res=pointerRecord.logChunkPointer(pipe, instanceReference, ptrField, instance, ch);
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
						if(typ==Object.class){
							var inst=field.get(ioPool, instance);
							if(inst==null){
								continue;
							}
							typ=inst.getClass();
						}
						if(IOFieldPrimitive.isPrimitive(typ)||typ.isEnum()) continue;
						if(typ.isArray()){
							var component=typ.componentType();
							if(UtilL.instanceOf(component, IOInstance.class)){
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
						if(UtilL.instanceOf(typ, IOInstance.class)){
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
												try(var io=instanceReference.io(cluster)){
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
			stack.remove(instance);
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
