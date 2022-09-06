package com.lapissea.cfs.type;

import com.lapissea.cfs.chunk.Chunk;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.logging.AverageDouble;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import static com.lapissea.cfs.GlobalConfig.DEBUG_VALIDATION;
import static com.lapissea.cfs.type.CommandSet.*;
import static com.lapissea.cfs.type.field.VirtualFieldDefinition.StoragePool.IO;

public class MemoryWalker{
	
	private static final class Timer{
		private long localTime=System.nanoTime();
		private long stamp;
		
		private void ignoreStart(){
			stamp=System.nanoTime();
		}
		private void ignoreEnd(){
			localTime+=System.nanoTime()-stamp;
		}
		
		private long diff(){
			return System.nanoTime()-localTime;
		}
	}
	
	
	public record Stat(AverageDouble localTime){
		public Stat(){
			this(new AverageDouble());
		}
	}
	
	private static final int DATA_MASK=0b00001;
	public static final  int SAVE     =0b00001;
	/**/
	private static final int FLOW_MASK=0b00110;
	public static final  int CONTINUE =0b00010;
	public static final  int END      =0b00100;
	
	private static final int INTERNAL_MASK=0b1000000000000000000000000000000;
	private static final int NO_RESULT    =0b1000000000000000000000000000000;
	
	
	private static int getFlow(int flags)       {return flags&FLOW_MASK;}
	private static boolean shouldSave(int flags){return UtilL.checkFlag(flags&DATA_MASK, SAVE);}
	private static boolean hasResult(int result){return !UtilL.checkFlag(result, NO_RESULT);}
	
	public interface PointerRecord{
		
		PointerRecord NOOP=of(r->{});
		
		static PointerRecord of(UnsafeConsumer<Reference, IOException> consumer){
			return new PointerRecord(){
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
			};
		}
		
		/**
		 * @return if walking should continue
		 */
		<T extends IOInstance<T>> int log(Reference instanceReference, T instance, IOField.Ref<T, ?> field, Reference valueReference) throws IOException;
		/**
		 * @return if walking should continue
		 */
		<T extends IOInstance<T>> int logChunkPointer(Reference instanceReference, T instance, IOField<T, ChunkPointer> field, ChunkPointer value) throws IOException;
	}
	
	private final DataProvider  provider;
	private final IOInstance    root;
	private final Reference     rootReference;
	private final StructPipe    pipe;
	private final PointerRecord pointerRecord;
	
	
	private final Map<Class<?>, Stat> stats=new HashMap<>();
	private final boolean             recordPerformance;
	
	public <T extends IOInstance.Unmanaged<T>> MemoryWalker(T root, boolean recordPerformance, PointerRecord pointerRecord){
		this(root.getDataProvider(), root, root.getReference(), root.getPipe(), recordPerformance, pointerRecord);
	}
	public <T extends IOInstance<T>> MemoryWalker(DataProvider provider, T root, Reference rootReference, StructPipe<T> pipe, boolean recordPerformance, PointerRecord pointerRecord){
		this.provider=provider;
		this.root=root;
		this.rootReference=rootReference;
		this.pipe=pipe;
		this.recordPerformance=recordPerformance;
		this.pointerRecord=pointerRecord;
	}
	
	public Map<Class<?>, Stat> getStats(){
		return stats;
	}
	
	@SuppressWarnings({"RedundantCast", "unchecked"})
	public <T extends IOInstance<T>> int walk() throws IOException{
		return walkStructFull((T)root, rootReference, (StructPipe<T>)pipe);
	}
	
	private <T extends IOInstance<T>> int walkStructFull(
		T instance, Reference instanceReference, StructPipe<T> pipe
	) throws IOException{
		return walkStructFull(instance, instanceReference, pipe, false);
	}
	
	@SuppressWarnings({"unchecked", "rawtypes"})
	private <T extends IOInstance<T>> int walkStructFull(
		T instance, Reference reference, StructPipe<T> pipe, boolean inlinedParent
	) throws IOException{
		if(instance==null||reference.isNull()){
			return CONTINUE;
		}
		var instanceStruct=instance.getThisStruct();
		if(!instanceStruct.getCanHavePointers()){
			return CONTINUE;
		}
		
		boolean inlineDirtyButContinue=false;
		
		var timer=recordPerformance?new Timer():null;
		
		try{
			var fieldOffset=0L;
			
			Iterator<IOField<T, ?>> iterator=pipe.getSpecificFields().iterator();
			
			CmdReader cmds  =pipe.getReferenceWalkCommands().reader();
			var       ioPool=instanceStruct.allocVirtualVarPool(IO);
			
			long    skipBits    =0;
			boolean dynamicPhase=false;
			wh:
			while(true){
				IOField<T, ?> field;
				{
					int cmd;
					
					while(true){
						cmd=cmds.cmd();
						if(cmd==UNMANAGED_REST){
							cmds=unmanagedCmd(instance);
							cmd=cmds.cmd();
						}
						
						if(cmd==ENDF) break wh;
						
						if(!iterator.hasNext()){
							if(dynamicPhase||!(instance instanceof IOInstance.Unmanaged unmanaged)) break wh;
							dynamicPhase=true;
							iterator=unmanaged.listUnmanagedFields().iterator();
							if(!iterator.hasNext()){
								break wh;
							}
						}
						
						field=iterator.next();
						
						if(skipBits>0) skipBits >>>= 1;
						boolean skipBit=(skipBits&1)==1;
						if(skipBit) continue wh;
						
						//repeat cmd cases
						if(cmd==SKIPF_IF_NULL){
							var offset=cmds.read8();
							
							var inst=field.get(ioPool, instance);
							if(inst!=null){
								if(inst instanceof Reference ref&&!ref.isNull()) continue;
								if(inst instanceof ChunkPointer ptr&&!ptr.isNull()) continue;
							}
							skipBits|=1L<<offset;
							continue;
						}
						break;
					}
					
					//no value cases
					switch(cmd){
						case SKIPB_B, SKIPB_I, SKIPB_L -> {
							fieldOffset+=knownSizeSkip(iterator, cmds, cmd);
							continue;
						}
						case SKIPB_UNKOWN -> {
							fieldOffset+=unknownSizeSkip(iterator, cmds, instance, ioPool, field);
							continue;
						}
					}
					
					var  sized=cmds.readBool();
					long size;
					
					if(sized){
						var sizeDesc=field.getSizeDescriptor();
						size=sizeDesc.calcUnknown(ioPool, provider, instance, WordSpace.BYTE);
					}else{
						size=0;
					}
					
					//legacy fallback
					if(cmd==POTENTIAL_REF){
						if(field instanceof IOField.Ref){
							cmd=REF_FIELD;
						}
					}
					
					try{
						var accessor=field.getAccessor();
						if(accessor==null) continue;
						
						//auto size advance
						switch(cmd){
							case REF_FIELD -> {
								var dynamic   =field.typeFlag(IOField.DYNAMIC_FLAG);
								var isInstance=field.typeFlag(IOField.IOINSTANCE_FLAG);
								
								IOField.Ref<T, T> refField=(IOField.Ref<T, T>)field;
								var               ref     =refField.getReference(instance);
								
								if(ref.isNull()) continue;
								
								{
									if(timer!=null) timer.ignoreStart();
									var res=pointerRecord.log(reference, instance, refField, ref);
									if(timer!=null) timer.ignoreEnd();
									
									checkResult(res);
									if(shouldSave(res)&&getFlow(res)==CONTINUE&&inlinedParent&&field.getSizeDescriptor().hasFixed()){
										inlineDirtyButContinue=true;
									}else{
										if(shouldSave(res)){
											if(inlinedParent) return SAVE|END;
											
											try(var io=reference.io(provider)){
												pipe.write(provider, io, instance);
											}
										}
										switch(getFlow(res)){
											case CONTINUE -> {}
											case END -> {return END;}
											default -> throw new NotImplementedException(getFlow(res)+"");
										}
									}
								}
								{
									if(!isInstance){
										continue;
									}
									if(!dynamic){
										var typ=refField.getAccessor().getType();
										if(!Struct.canUnknownHavePointers(typ)){
											continue;
										}
									}
									
									var instRefField=(IOField.Ref<T, T> & IOField.Ref.Inst<T, T>)refField;
									
									if(timer!=null) timer.ignoreStart();
									var res=walkStructFull(instRefField.get(ioPool, instance), ref, instRefField.getReferencedPipe(instance), false);
									if(timer!=null) timer.ignoreEnd();
									if(shouldSave(res)){
										throw new NotImplementedException();//TODO
									}
									switch(getFlow(res)){
										case CONTINUE -> {}
										case END -> {return END;}
										default -> throw new NotImplementedException(getFlow(res)+"");
									}
								}
							}
							case DYNAMIC -> {
								var inst=field.get(ioPool, instance);
								if(inst==null) continue;
								
								if(inst instanceof IOInstance.Unmanaged valueInstance){
									if(timer!=null) timer.ignoreStart();
									var res=walkStructFull(valueInstance, valueInstance.getReference(), valueInstance.getPipe(), false);
									if(timer!=null) timer.ignoreEnd();
									
									if(shouldSave(res)){
										throw new NotImplementedException();//TODO
									}
									switch(res&FLOW_MASK){
										case CONTINUE -> {
											continue;
										}
										case END -> {return END;}
										default -> throw new NotImplementedException((res&FLOW_MASK)+"");
									}
								}
								
								
								if(inst instanceof IOInstance fieldValueInstance){
									if(!fieldValueInstance.getThisStruct().getCanHavePointers()) continue;
									{
										if(timer!=null) timer.ignoreStart();
										var res=walkStructFull(fieldValueInstance, reference.addOffset(fieldOffset), StructPipe.of(pipe.getClass(), fieldValueInstance.getThisStruct()), true);
										if(timer!=null) timer.ignoreEnd();
										
										var result=handleResult(ioPool, instance, pipe, inlinedParent, reference, (IOField<T, Object>)field, inst, res);
										if(hasResult(result)) return result;
									}
								}
							}
							case CHPTR -> {
								var result=handlePtr(instance, pipe, reference, ioPool, (IOField<T, ChunkPointer>)field);
								if(hasResult(result)) return result;
							}
							case POTENTIAL_REF -> {
								
								Class<?> type=accessor.getType();
								
								var isInstance=field.typeFlag(IOField.IOINSTANCE_FLAG);
								
								{
									if(type.isArray()){
										var component=type.componentType();
										if(IOInstance.isInstance(component)){
											if(!Struct.canUnknownHavePointers(component)){
												continue;
											}
											var array=(IOInstance<?>[])field.get(ioPool, instance);
											if(array==null||array.length==0) continue;
											var pip=StructPipe.of(pipe.getClass(), array[0].getThisStruct());
											for(IOInstance<?> inst : array){
												{
													if(timer!=null) timer.ignoreStart();
													var res=walkStructFull((T)inst, reference.addOffset(fieldOffset), pip, true);
													if(timer!=null) timer.ignoreEnd();
													if(shouldSave(res)){
														throw new NotImplementedException();//TODO
													}
													switch(getFlow(res)){
														case CONTINUE -> {}
														case END -> {return END;}
														default -> throw new NotImplementedException(getFlow(res)+"");
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
												if(timer!=null) timer.ignoreStart();
												var res=walkStructFull((T)fieldValue, reference.addOffset(fieldOffset), StructPipe.of(pipe.getClass(), fieldValue.getThisStruct()), true);
												if(timer!=null) timer.ignoreEnd();
												if(shouldSave(res)&&getFlow(res)==CONTINUE&&inlinedParent){
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
									
									throw new RuntimeException(TextUtil.toString("unmanaged walk type:", type.toString(), field.getAccessor()));
								}
							}
							default -> throw new NotImplementedException(cmd+"");
						}
					}catch(IOException e){
						String instStr=instanceErrStr(instance);
						throw new IOException("IO problem on "+field+" in "+instStr, e);
					}catch(Throwable e){
						String instStr=instanceErrStr(instance);
						throw new RuntimeException("failed to walk on "+field+" in "+instStr, e);
					}finally{
						if(sized){
							fieldOffset+=size;
						}
					}
				}
			}
		}finally{
			if(timer!=null){
				var diff=timer.diff()/1000_000D;
				var info=stats.computeIfAbsent(instance.getClass(), c->new Stat());
				info.localTime().accept(diff);
			}
		}
		if(inlineDirtyButContinue){
			return CONTINUE|SAVE;
		}
		return CONTINUE;
	}
	
	private <T extends IOInstance<T>> long unknownSizeSkip(Iterator<IOField<T, ?>> iterator, CmdReader cmds, T instance, Struct.Pool<T> ioPool, IOField<T, ?> field){
		var  extra   =cmds.read8();
		var  sizeDesc=field.getSizeDescriptor();
		long skipSize=sizeDesc.calcUnknown(ioPool, provider, instance, WordSpace.BYTE);
		
		for(int i=0;i<extra;i++){
			iterator.next();
		}
		return skipSize;
	}
	
	private static <T extends IOInstance<T>> long knownSizeSkip(Iterator<IOField<T, ?>> iterator, CmdReader cmds, int cmd){
		var extra=cmds.read8();
		var siz=switch(cmd){
			case SKIPB_B -> cmds.read8();
			case SKIPB_I -> cmds.read32();
			case SKIPB_L -> cmds.read64();
			default -> throw new ShouldNeverHappenError();
		};
		for(int i=0;i<extra;i++){
			iterator.next();
		}
		return siz;
	}
	
	private static <T extends IOInstance<T>> CmdReader unmanagedCmd(IOInstance<T> instance){
		return ((IOInstance.Unmanaged<?>)instance).getUnmanagedReferenceWalkCommands();
	}
	
	private <T extends IOInstance<T>> int handlePtr(T instance, StructPipe<T> pipe, Reference reference, Struct.Pool<T> ioPool, IOField<T, ChunkPointer> ptrField) throws IOException{
		var ch=ptrField.get(ioPool, instance);
		
		if(!ch.isNull()){
			{
				var res=pointerRecord.logChunkPointer(reference, instance, ptrField, ch);
				checkResult(res);
				if(shouldSave(res)){
					throw new NotImplementedException();//TODO
				}
				switch(getFlow(res)){
					case CONTINUE -> {}
					case END -> {return END;}
					default -> throw new NotImplementedException(getFlow(res)+"");
				}
			}
			{
				var c  =ch.dereference(provider);
				var res=walkStructFull(c, makeChunkRef(c), Chunk.PIPE, false);
				if(shouldSave(res)){
					throw new NotImplementedException();//TODO
				}
				switch(getFlow(res)){
					case CONTINUE -> {}
					case END -> {return END;}
					default -> throw new NotImplementedException(getFlow(res)+"");
				}
			}
		}
		return NO_RESULT;
	}
	
	private Reference makeChunkRef(Chunk c) throws IOException{
		var off=provider.getFirstChunk().getPtr();
		return new Reference(off, c.getPtr().getValue()-off.getValue());
	}
	
	private <T extends IOInstance<T>, FT> int handleResult(
		Struct.Pool<T> ioPool, T instance, StructPipe<T> pipe,
		boolean inlinedParent, Reference instanceReference,
		IOField<T, FT> field, FT fieldValue,
		int res
	) throws IOException{
		if(shouldSave(res)){
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
		
		switch(getFlow(res)){
			case CONTINUE -> {return NO_RESULT;}
			case END -> {return END;}
			default -> throw new NotImplementedException(getFlow(res)+"");
		}
	}
	
	private void checkResult(int res){
		if(provider.isReadOnly()){
			if(shouldSave(res)){
				throw new IllegalStateException("Tried to save on read only walk");
			}
		}
		
		if(DEBUG_VALIDATION){
			var data=res&FLOW_MASK;
			if(data!=CONTINUE&&data!=END){
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
