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
	
	public interface PointerRecord{
		/**
		 * @return if walking should continue
		 */
		<T extends IOInstance<T>> boolean log(StructPipe<T> pipe, Reference instanceReference, IOField.Ref<T, ?> field, T instance, Reference value) throws IOException;
		/**
		 * @return if walking should continue
		 */
		<T extends IOInstance<T>> boolean logChunkPointer(StructPipe<T> pipe, Reference instanceReference, IOField<T, ChunkPointer> field, T instance, ChunkPointer value) throws IOException;
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
			public <I extends IOInstance<I>> boolean log(StructPipe<I> pipe, Reference instanceReference, IOField.Ref<I, ?> field, I instance, Reference value) throws IOException{
				consumer.accept(value);
				return true;
			}
			@Override
			public <I extends IOInstance<I>> boolean logChunkPointer(StructPipe<I> pipe, Reference instanceReference, IOField<I, ChunkPointer> field, I instance, ChunkPointer value) throws IOException{
				consumer.accept(value.makeReference());
				return true;
			}
		});
	}
	
	public <T extends IOInstance<T>> void walk(PointerRecord consumer) throws IOException{
		walkStructFull(cluster, new LinkedList<>(), (T)root, rootReference, (StructPipe<T>)pipe, consumer);
	}
	
	@SuppressWarnings("unchecked")
	private <T extends IOInstance<T>> boolean walkStructFull(DataProvider cluster, List<IOInstance<?>> stack,
	                                                         T instance, Reference instanceReference, StructPipe<T> pipe,
	                                                         PointerRecord pointerRecord) throws IOException{
		var reference=instanceReference;
		if(instance instanceof Chunk c){
			var off=cluster.getFirstChunk().getPtr();
			reference=new Reference(off, c.getPtr().getValue()-off.getValue());
		}
		if(reference==null||reference.isNull()) return true;
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
				return true;
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
					if(stack.size()==4&&field.toString().equals("#value")){
						LogUtil.println("AAAAAA");
					}
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
							if(!walkStructFull(cluster, stack, valueInstance, valueInstance.getReference(), valueInstance.getPipe(), pointerRecord)) return false;
							continue;
						}
					}
					
					if(field instanceof IOField.Ref<?, ?> refO){
						IOField.Ref<T, T> refField=(IOField.Ref<T, T>)refO;
						var               ref     =refField.getReference(instance);
						if(!pointerRecord.log(pipe, instanceReference, refField, instance, ref)) return false;
						if(!walkStructFull(cluster, stack, refField.get(ioPool, instance), ref, refField.getReferencedPipe(instance), pointerRecord)) return false;
					}else if(UtilL.instanceOf(type, ChunkPointer.class)){
						var ptrField=(IOField<T, ChunkPointer>)field;
						
						var ch=ptrField.get(ioPool, instance);
						
						if(!ch.isNull()){
							if(!pointerRecord.logChunkPointer(pipe, instanceReference, ptrField, instance, ch)) return false;
							if(!walkStructFull(cluster, stack, ch.dereference(cluster), null, Chunk.PIPE, pointerRecord)) return false;
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
						if(UtilL.instanceOf(typ, IOInstance.class)){
							var inst=(IOInstance<?>)field.get(ioPool, instance);
							if(inst!=null){
								if(!walkStructFull(cluster, stack, (T)inst, reference.addOffset(fieldOffset), StructPipe.of(pipe.getClass(), inst.getThisStruct()), pointerRecord)) return false;
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
								if(!walkStructFull(cluster, stack, i, reference.addOffset(fieldOffset), StructPipe.of(pipe.getClass(), i.getThisStruct()), pointerRecord)) return false;
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
		return true;
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
	
	
}
