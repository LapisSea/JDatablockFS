package com.lapissea.dfs.type;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.logging.AverageDouble;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.objects.Reference;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.fields.RefField;
import com.lapissea.dfs.type.field.fields.reflection.IOFieldInlineObject;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.lapissea.dfs.config.GlobalConfig.DEBUG_VALIDATION;
import static com.lapissea.dfs.type.CommandSet.*;
import static com.lapissea.dfs.type.field.StoragePool.IO;

public class MemoryWalker{
	
	private static class MemoryWalkIOFail extends IOException{
		
		private final List<String> stack = new ArrayList<>();
		private       String       msg;
		
		public MemoryWalkIOFail(Throwable cause){
			super(cause);
		}
		
		@Override
		public String getMessage(){
			if(msg == null) msg = "IO problem while walking on:\n" + String.join("\n", stack);
			return msg;
		}
		
		private void add(Object obj){
			stack.add(instanceErrStr(obj));
			msg = null;
		}
	}
	
	
	private static final class Timer{
		private long localTime = System.nanoTime();
		private long stamp;
		
		private void ignoreStart(){
			stamp = System.nanoTime();
		}
		private void ignoreEnd(){
			localTime += System.nanoTime() - stamp;
		}
		
		private long diff(){
			return System.nanoTime() - localTime;
		}
	}
	
	
	public record Stat(AverageDouble localTime){
		public Stat(){
			this(new AverageDouble());
		}
	}
	
	private static final int DATA_MASK   = 0b000011;
	public static final  int SAVE        = 0b000001;
	public static final  int HOLDER_COPY = 0b000010;
	/**/
	private static final int FLOW_MASK   = 0b001100;
	public static final  int CONTINUE    = 0b000100;
	public static final  int END         = 0b001000;
	
	private static final int INTERNAL_MASK = 0b1000000000000000000000000000000;
	private static final int NO_RESULT     = 0b1000000000000000000000000000000;
	
	
	private static int getFlow(int flags)             { return flags&FLOW_MASK; }
	private static int getDataAction(int flags)       { return flags&DATA_MASK; }
	private static boolean shouldHolderCopy(int flags){ return UtilL.checkFlag(getDataAction(flags), HOLDER_COPY); }
	private static boolean shouldSave(int flags)      { return UtilL.checkFlag(getDataAction(flags), SAVE); }
	private static boolean hasResult(int result)      { return !UtilL.checkFlag(result, NO_RESULT); }
	
	public interface PointerRecord{
		
		class Holder{
			public Object value;
		}
		
		PointerRecord NOOP = of(r -> { });
		
		static PointerRecord of(UnsafeConsumer<Reference, IOException> consumer){
			return new PointerRecord(){
				@Override
				public <I extends IOInstance<I>> int log(Reference instanceReference, I instance, RefField<I, ?> field, Reference valueReference, Holder holder) throws IOException{
					consumer.accept(valueReference);
					return CONTINUE;
				}
				@Override
				public <I extends IOInstance<I>> int logChunkPointer(Reference instanceReference, I instance, IOField<I, ChunkPointer> field, ChunkPointer value, Holder holder) throws IOException{
					consumer.accept(value.makeReference());
					return CONTINUE;
				}
			};
		}
		
		/**
		 * @return if walking should continue
		 */
		<T extends IOInstance<T>> int log(Reference instanceReference, T instance, RefField<T, ?> field, Reference valueReference, Holder holder) throws IOException;
		/**
		 * @return if walking should continue
		 */
		<T extends IOInstance<T>> int logChunkPointer(Reference instanceReference, T instance, IOField<T, ChunkPointer> field, ChunkPointer value, Holder holder) throws IOException;
	}
	
	private final DataProvider  provider;
	private final IOInstance    root;
	private final Reference     rootReference;
	private final StructPipe    pipe;
	private final PointerRecord pointerRecord;
	
	
	private final Map<Class<?>, Stat> stats = new HashMap<>();
	private final boolean             recordPerformance;
	
	public <T extends IOInstance.Unmanaged<T>> MemoryWalker(T root, boolean recordPerformance, PointerRecord pointerRecord){
		this(root.getDataProvider(), root, root.getPointer().makeReference(), root.getPipe(), recordPerformance, pointerRecord);
	}
	public <T extends IOInstance<T>> MemoryWalker(DataProvider provider, T root, Reference rootReference, StructPipe<T> pipe, boolean recordPerformance, PointerRecord pointerRecord){
		this.provider = provider;
		this.root = root;
		this.rootReference = rootReference;
		this.pipe = pipe;
		this.recordPerformance = recordPerformance;
		this.pointerRecord = pointerRecord;
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
		var holder = new PointerRecord.Holder();
		var res    = walkStructFull(instance, instanceReference, pipe, false, holder);
		
		if(shouldHolderCopy(res)){
			throw new UnsupportedOperationException();
		}
		return res;
	}
	
	@SuppressWarnings({"unchecked", "rawtypes"})
	private <T extends IOInstance<T>> int walkStructFull(
		T instance, Reference reference, StructPipe<T> pipe, boolean inlinedParent, PointerRecord.Holder parentHolder
	) throws IOException{
		if(instance == null || reference.isNull()){
			return CONTINUE;
		}
		var instanceStruct = instance.getThisStruct();
		if(!instanceStruct.getCanHavePointers()){
			return CONTINUE;
		}
		
		boolean inlineDirtyButContinue = false;
		
		var timer = recordPerformance? new Timer() : null;
		
		var holder = new PointerRecord.Holder();
		
		try{
			var fieldOffset = 0L;
			
			Iterator<IOField<T, ?>> iterator = pipe.getSpecificFields().iterator();
			
			CmdReader cmds   = pipe.getReferenceWalkCommands().reader();
			var       ioPool = instanceStruct.allocVirtualVarPool(IO);
			
			int skipBits     = 0;
			var dynamicPhase = false;
			wh:
			while(true){
				IOField<T, ?> field = null;
				{
					int cmd;
					var nextField = true;
					while(true){
						cmd = cmds.cmd();
						if(cmd == UNMANAGED_REST){
							cmds = unmanagedCmd(instance);
							cmd = cmds.cmd();
						}
						
						if(cmd == ENDF) break wh;
						
						if(!iterator.hasNext()){
							if(dynamicPhase || !(instance instanceof IOInstance.Unmanaged unmanaged)) break wh;
							dynamicPhase = true;
							iterator = unmanaged.listUnmanagedFields().iterator();
							if(!iterator.hasNext()){
								break wh;
							}
						}
						
						if(nextField){
							field = iterator.next();
							
							if(skipBits>0) skipBits >>>= 1;
							boolean skipBit = (skipBits&1) == 1;
							if(skipBit) continue wh;
						}
						
						//repeat cmd cases
						if(cmd == SKIPF_IF_NULL){
							var offset = cmds.read8();
							
							var inst = field.get(ioPool, instance);
							
							if(inst == null ||
							   inst instanceof Reference ref && ref.isNull() ||
							   inst instanceof ChunkPointer ptr && ptr.isNull()){
								skipBits |= 1L<<offset;
							}
							nextField = false;
							continue;
						}
						break;
					}
					
					//no value cases
					switch(cmd){
						case SKIPB_B, SKIPB_I, SKIPB_L -> {
							fieldOffset += knownSizeSkip(iterator, cmds, cmd);
							continue;
						}
						case SKIPB_UNKNOWN -> {
							fieldOffset += unknownSizeSkip(iterator, cmds, instance, ioPool, field);
							continue;
						}
					}
					
					var  sized = cmds.readBool();
					long size;
					
					if(sized){
						var sizeDesc = field.getSizeDescriptor();
						size = sizeDesc.calcUnknown(ioPool, provider, instance, WordSpace.BYTE);
					}else{
						size = 0;
					}
					
					//legacy fallback
					if(cmd == POTENTIAL_REF || cmd == DYNAMIC){
						if(field instanceof RefField){
							cmd = REF_FIELD;
						}
					}
					
					try{
						var accessor = field.getAccessor();
						if(accessor == null) continue;
						
						//auto size advance
						switch(cmd){
							case REF_FIELD -> {
								var dynamic    = field.typeFlag(IOField.DYNAMIC_FLAG);
								var isInstance = field.typeFlag(IOField.IO_INSTANCE_FLAG);
								
								RefField<T, T> refField = (RefField<T, T>)field;
								var            ref      = refField.getReference(instance);
								
								if(ref.isNull()) continue;
								
								{
									if(timer != null) timer.ignoreStart();
									var res = pointerRecord.log(reference, instance, refField, ref, holder);
									if(timer != null) timer.ignoreEnd();
									
									if(shouldHolderCopy(res)){
										res = (res&(~DATA_MASK))|SAVE;
										var val = holder.value;
										holder.value = null;
										instance = copyWith(instance, field, val);
									}
									
									checkResult(res);
									if(shouldSave(res) && getFlow(res) == CONTINUE && inlinedParent && field.getSizeDescriptor().hasFixed()){
										inlineDirtyButContinue = true;
									}else{
										if(shouldSave(res)){
											if(inlinedParent) return SAVE|END;
											
											reference.write(provider, false, pipe, instance);
										}
										switch(getFlow(res)){
											case CONTINUE -> { }
											case END -> { return END; }
											default -> throw failFlow(res);
										}
									}
								}
								if(ref.getOffset() == 0){
									var ch   = ref.getPtr().dereference(provider);
									var flow = walkChunk(ch);
									switch(flow){
										case CONTINUE -> { }
										case END -> { return END|(inlineDirtyButContinue? CONTINUE : 0); }
										default -> throw failFlow(flow);
									}
								}
								{
									if(!isInstance){
										continue;
									}
									if(!dynamic){
										var typ = refField.getType();
										if(!Struct.canUnknownHavePointers(typ)){
											continue;
										}
									}
									
									var instRefField = (RefField<T, T> & RefField.Inst<T, T>)refField;
									
									if(timer != null) timer.ignoreStart();
									var res = walkStructFull(instRefField.get(ioPool, instance), ref, instRefField.getReferencedPipe(instance), false, parentHolder);
									if(timer != null) timer.ignoreEnd();
									
									if(shouldSave(res)){
										throw new NotImplementedException("Saving a referenced instance is not implemented yet");//TODO
									}
									switch(getFlow(res)){
										case CONTINUE -> { }
										case END -> { return END; }
										default -> throw failFlow(res);
									}
								}
							}
							case DYNAMIC -> {
								var inst = field.get(ioPool, instance);
								if(inst == null) continue;
								
								if(inst instanceof IOInstance.Unmanaged valueInstance){
									{
										var ptr       = valueInstance.getPointer();
										var uRefField = getNoIO(instance, valueInstance, field);
										if(timer != null) timer.ignoreStart();
										var res = pointerRecord.log(reference, instance, uRefField, ptr.makeReference(), holder);
										if(timer != null) timer.ignoreEnd();
										
										if(shouldHolderCopy(res)){
											res = (res&(~DATA_MASK))|SAVE;
											var val = holder.value;
											holder.value = null;
											instance = copyWith(instance, field, val);
										}
										
										checkResult(res);
										if(shouldSave(res) && getFlow(res) == CONTINUE && inlinedParent && field.getSizeDescriptor().hasFixed()){
											inlineDirtyButContinue = true;
										}else{
											if(shouldSave(res)){
												if(inlinedParent) return SAVE|END;
												
												reference.write(provider, false, pipe, instance);
											}
											switch(getFlow(res)){
												case CONTINUE -> { }
												case END -> { return END; }
												default -> throw failFlow(res);
											}
										}
										
										var ch   = ptr.dereference(provider);
										var flow = walkChunk(ch);
										switch(flow){
											case CONTINUE -> { }
											case END -> { return END; }
											default -> throw failFlow(flow);
										}
									}
									
									if(timer != null) timer.ignoreStart();
									var res = walkStructFull(valueInstance, valueInstance.getPointer().makeReference(), valueInstance.getPipe(), false, parentHolder);
									if(timer != null) timer.ignoreEnd();
									
									if(shouldSave(res)){
										throw new NotImplementedException("Saving a dynamic unmanged instance is not implemented yet");//TODO
									}
									switch(res&FLOW_MASK){
										case CONTINUE -> {
											continue;
										}
										case END -> { return END; }
										default -> throw new NotImplementedException(String.valueOf(res&FLOW_MASK));
									}
								}
								
								
								if(inst instanceof IOInstance fieldValueInstance){
									if(!fieldValueInstance.getThisStruct().getCanHavePointers()) continue;
									{
										StructPipe instancePipe = getPipe(dynamicPhase, instance, field, pipe, fieldValueInstance);
										
										if(timer != null) timer.ignoreStart();
										var res = walkStructFull(fieldValueInstance, reference.addOffset(fieldOffset), instancePipe, true, parentHolder);
										if(timer != null) timer.ignoreEnd();
										
										var result = handleResult(ioPool, instance, pipe, inlinedParent, reference, (IOField<T, Object>)field, inst, res, parentHolder);
										if(hasResult(result)) return result;
									}
								}
							}
							case CHPTR -> {
								var result = handlePtr(instance, pipe, reference, ioPool, (IOField<T, ChunkPointer>)field, inlinedParent, parentHolder);
								if(hasResult(result)) return result;
							}
							case POTENTIAL_REF -> {
								
								Class<?> type = accessor.getType();
								
								var isInstance = field.typeFlag(IOField.IO_INSTANCE_FLAG);
								
								{
									if(type.isArray()){
										var component = type.componentType();
										if(IOInstance.isInstance(component)){
											if(!Struct.canUnknownHavePointers(component)){
												continue;
											}
											var array = (IOInstance<?>[])field.get(ioPool, instance);
											if(array == null || array.length == 0) continue;
											StructPipe pip = getPipe(dynamicPhase, instance, field, pipe, array[0]);
											
											for(IOInstance<?> inst : array){
												{
													if(timer != null) timer.ignoreStart();
													var res = walkStructFull((T)inst, reference.addOffset(fieldOffset), pip, true, parentHolder);
													if(timer != null) timer.ignoreEnd();
													if(shouldSave(res)){
														throw new NotImplementedException("Saving an array of instances is not implemented yet");//TODO
													}
													switch(getFlow(res)){
														case CONTINUE -> { }
														case END -> { return END; }
														default -> throw failFlow(res);
													}
												}
												fieldOffset += pip.calcUnknownSize(provider, inst, WordSpace.BYTE);
											}
											continue;
										}
										if(SupportedPrimitive.isAny(component)){
											continue;
										}
										if(component == String.class){
											continue;
										}
									}
									if(isInstance){
										var fieldValue = (IOInstance<?>)field.get(ioPool, instance);
										if(fieldValue != null){
											{
												StructPipe pip = getPipe(dynamicPhase, instance, field, pipe, fieldValue);
												
												if(timer != null) timer.ignoreStart();
												var res = walkStructFull((T)fieldValue, reference.addOffset(fieldOffset), pip, true, parentHolder);
												if(timer != null) timer.ignoreEnd();
												if(shouldSave(res) && getFlow(res) == CONTINUE && inlinedParent){
													inlineDirtyButContinue = true;
												}else{
													var result = handleResult(ioPool, instance, pipe, inlinedParent, reference, (IOField<T, IOInstance<?>>)field, fieldValue, res, parentHolder);
													if(hasResult(result)) return result;
												}
											}
										}
										continue;
									}
									if(SupportedPrimitive.isAny(type)){
										continue;
									}
									if(type == String.class){
										continue;
									}
									
									throw new RuntimeException(TextUtil.toString("unmanaged walk type:", type.toString(), field.getAccessor()));
								}
							}
							default -> throw new NotImplementedException(String.valueOf(cmd));
						}
					}catch(MemoryWalkIOFail e){
						e.add(instance);
						throw e;
					}catch(IOException e){
						var er = new MemoryWalkIOFail(e);
						er.add(instance);
						throw er;
					}catch(Throwable e){
						String instStr = instanceErrStr(instance);
						throw new RuntimeException("failed to walk on " + field + " in " + instStr, e);
					}finally{
						if(sized){
							fieldOffset += size;
						}
					}
				}
			}
		}finally{
			if(timer != null){
				var diff = timer.diff()/1000_000D;
				var info = stats.computeIfAbsent(instance.getClass(), c -> new Stat());
				info.localTime().accept(diff);
			}
		}
		if(inlineDirtyButContinue){
			return CONTINUE|SAVE;
		}
		return CONTINUE;
	}
	
	@SuppressWarnings("rawtypes")
	private <T extends IOInstance<T>> RefField<T, T> getNoIO(T instance, IOInstance.Unmanaged valueInstance, IOField<T, ?> field){
		return new RefField.NoIO<>(field.getAccessor(), field.getSizeDescriptor()){
			@Override
			protected Set<TypeFlag> computeTypeFlags(){
				throw NotImplementedException.infer();//TODO: implement .computeTypeFlags()
			}
			@Override
			public void setReference(T inst, Reference newRef) throws IOException{
				if(inst != instance) throw new IllegalStateException();
				valueInstance.notifyReferenceMovement(newRef.asJustChunk(provider));
			}
			@Override
			public Reference getReference(T inst){
				if(inst != instance) throw new IllegalStateException();
				return valueInstance.getPointer().makeReference();
			}
			@SuppressWarnings("unchecked")
			@Override
			public StructPipe<T> getReferencedPipe(T inst){
				if(inst != instance) throw new IllegalStateException();
				return valueInstance.getPipe();
			}
		};
	}
	
	private <T extends IOInstance<T>, V> T copyWith(T instance, IOField<T, V> field, Object val){
		var instanceStruct = instance.getThisStruct();
		if(!field.isReadOnly()){
			//noinspection unchecked
			field.set(null, instance, (V)val);
			return instance;
		}
		
		var builderStruct = instanceStruct.getBuilderObjType(false);
		
		var builder = builderStruct.make();
		builder.copyFrom(instance);
		//noinspection unchecked
		var f = builderStruct.getFields().requireExact((Class<Object>)field.getType(), field.getName());
		f.set(null, builder, val);
		return builder.build();
	}
	
	private static final IOField<Chunk, ChunkPointer> CH_NEXT_PTR = Chunk.STRUCT.getFields().requireExact(ChunkPointer.class, "nextPtr");
	
	private int walkChunk(Chunk instance) throws IOException{
		var ch = instance;
		while(true){
			var nextPtr = ch.getNextPtr();
			if(nextPtr.isNull()) return CONTINUE;
			var res = pointerRecord.logChunkPointer(makeChunkRef(ch), ch, CH_NEXT_PTR, ch.getNextPtr(), new PointerRecord.Holder());
			
			if(shouldHolderCopy(res)){
				throw new UnsupportedOperationException();
			}
			
			if(shouldSave(res)){
				ch.syncStruct();
			}
			switch(getFlow(res)){
				case CONTINUE -> ch = nextPtr.dereference(provider);
				case END -> { return END; }
				default -> throw failFlow(res);
			}
		}
	}
	
	private static IllegalStateException failFlow(int res){
		var flow = getFlow(res);
		return new IllegalStateException(flow + " is not a valid flow, please provide any of [CONTINUE, END]");
	}
	
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static <T extends IOInstance<T>> StructPipe getPipe(
		boolean dynamic, T instance, IOField<T, ?> field, StructPipe<T> parentPipe, IOInstance fieldValue
	){
		if(dynamic) return ((IOInstance.Unmanaged)instance).getFieldPipe(field, fieldValue);
		if(field instanceof IOFieldInlineObject objField){
			return objField.getInstancePipe();
		}
		return StructPipe.of(parentPipe.getClass(), fieldValue.getThisStruct());
	}
	
	private <T extends IOInstance<T>> long unknownSizeSkip(Iterator<IOField<T, ?>> iterator, CmdReader cmds, T instance, VarPool<T> ioPool, IOField<T, ?> field){
		var  extra    = cmds.read8();
		var  sizeDesc = field.getSizeDescriptor();
		long skipSize = sizeDesc.calcUnknown(ioPool, provider, instance, WordSpace.BYTE);
		
		for(int i = 0; i<extra; i++){
			iterator.next();
		}
		return skipSize;
	}
	
	private static <T extends IOInstance<T>> long knownSizeSkip(Iterator<IOField<T, ?>> iterator, CmdReader cmds, int cmd){
		var extra = cmds.read8();
		var siz = switch(cmd){
			case SKIPB_B -> cmds.read8();
			case SKIPB_I -> cmds.read32();
			case SKIPB_L -> cmds.read64();
			default -> throw new ShouldNeverHappenError();
		};
		for(int i = 0; i<extra; i++){
			iterator.next();
		}
		return siz;
	}
	
	private static <T extends IOInstance<T>> CmdReader unmanagedCmd(IOInstance<T> instance){
		return ((IOInstance.Unmanaged<?>)instance).getUnmanagedReferenceWalkCommands();
	}
	
	private <T extends IOInstance<T>> int handlePtr(
		T instance, StructPipe<T> pipe, Reference reference, VarPool<T> ioPool, IOField<T, ChunkPointer> ptrField, boolean inlinedParent, PointerRecord.Holder holder
	) throws IOException{
		var ch = ptrField.get(ioPool, instance);
		
		if(!ch.isNull()){
			{
				var res = pointerRecord.logChunkPointer(reference, instance, ptrField, ch, holder);
				checkResult(res);
				
				switch(getDataAction(res)){
					case SAVE -> {
						if(inlinedParent) return SAVE|END;
						reference.write(provider, false, pipe, instance);
					}
					case HOLDER_COPY -> {
						var val = holder.value;
						holder.value = null;
						instance = copyWith(instance, ptrField, val);
						if(inlinedParent){
							holder.value = instance;
							return HOLDER_COPY|END;
						}
						reference.write(provider, false, pipe, instance);
					}
				}
				
				switch(getFlow(res)){
					case CONTINUE -> { }
					case END -> { return END; }
					default -> throw failFlow(res);
				}
			}
			{
				var flow = walkChunk(ch.dereference(provider));
				switch(flow){
					case CONTINUE -> { }
					case END -> { return END; }
					default -> throw failFlow(flow);
				}
			}
		}
		return NO_RESULT;
	}
	
	private Reference makeChunkRef(Chunk c) throws IOException{
		var off = provider.getFirstChunk().getPtr();
		return new Reference(off, c.getPtr().getValue() - off.getValue());
	}
	
	private <T extends IOInstance<T>, FT> int handleResult(
		VarPool<T> ioPool, T instance, StructPipe<T> pipe,
		boolean inlinedParent, Reference instanceReference,
		IOField<T, FT> field, FT fieldValue,
		int res, PointerRecord.Holder holder
	) throws IOException{
		
		if(shouldHolderCopy(res)){
			res = (res&(~DATA_MASK))|SAVE;
			var val = holder.value;
			holder.value = null;
			instance = copyWith(instance, field, val);
			
			if(inlinedParent){
				holder.value = instance;
				return END|HOLDER_COPY;
			}
		}
		
		if(shouldSave(res)){
			if(inlinedParent){
				return SAVE|END;
			}
			if(instance instanceof IOInstance.Unmanaged<?>){
				field.set(ioPool, instance, fieldValue);
			}else{
				instanceReference.write(provider, false, pipe, instance);
			}
		}
		
		switch(getFlow(res)){
			case CONTINUE -> { return NO_RESULT; }
			case END -> { return END; }
			default -> throw failFlow(res);
		}
	}
	
	private void checkResult(int res){
		if(provider.isReadOnly()){
			if(getDataAction(res) != 0){
				throw new IllegalStateException("Tried to save on read only walk");
			}
		}
		
		if(DEBUG_VALIDATION){
			var data = res&FLOW_MASK;
			if(data != CONTINUE && data != END){
				throw new IllegalStateException("no flow flag provided");
			}
		}
	}
	
	private static String instanceErrStr(Object instance){
		String instStr;
		try{
			instStr = instance.toString();
		}catch(Throwable e1){
			instStr = "<err toString " + e1.getMessage() + " for " + instance.getClass().getName() + ">";
		}
		return instStr;
	}
	
	public IOInstance getRoot(){
		return root;
	}
}
