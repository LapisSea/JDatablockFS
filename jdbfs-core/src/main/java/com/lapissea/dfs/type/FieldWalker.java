package com.lapissea.dfs.type;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.objects.Reference;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.StoragePool;
import com.lapissea.dfs.type.field.fields.RefField;
import com.lapissea.util.UtilL;

import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

public final class FieldWalker{
	
	
	private static final int DATA_MASK = 0b00001;
	public static final  int SAVE      = 0b00001;
	/**/
	private static final int FLOW_MASK = 0b00110;
	public static final  int CONTINUE  = 0b00010;
	public static final  int END       = 0b00100;
	
	private static int getFlow(int flags)       { return flags&FLOW_MASK; }
	private static boolean shouldSave(int flags){ return UtilL.checkFlag(flags&DATA_MASK, SAVE); }
	
	private static IllegalStateException failFlow(int res){
		var flow = getFlow(res);
		return new IllegalStateException(flow + " is not a valid flow, please provide any of [CONTINUE, END]");
	}
	
	public interface FieldRecord{
		<I extends IOInstance<I>> int log(I instance, IOField<I, ?> field);
	}
	
	public static <T extends IOInstance<T>> void walk(DataProvider provider, T instance, FieldRecord record) throws IOException{
		walk0(provider, instance, record, null, null, false);
	}
	
	private static <T extends IOInstance<T>, V> int walkField(
		DataProvider provider, T instance, FieldRecord record,
		StructPipe<T> instancePipe, Reference instanceReference, long fieldOffset, IOField<T, V> field
	) throws IOException{
		
		var o = field.get(null, instance);
		if(o == null) return CONTINUE;
		List<IOInstance> vals = switch(o){
			case IOInstance v -> List.of(v);
			case List<?> l -> (List<IOInstance>)l;
			case IOInstance[] ar -> Arrays.asList(ar);
			default -> throw new IllegalStateException("Unexpected value: " + o);
		};
		
		boolean save = false;
		boolean end  = false;
		
		for(var val : vals){
			StructPipe pipe;
			Reference  ref;
			boolean    inlineParent;
			if(field instanceof RefField<T, ?> refF){
				ref = refF.getReference(instance);
				pipe = (StructPipe<?>)refF.getReferencedPipe(instance);
				inlineParent = false;
			}else{
				if(instanceReference == null){
					ref = null;
					pipe = null;
				}else{
					ref = instanceReference.addOffset(fieldOffset);
					pipe = StructPipe.of(instancePipe.getClass(), val.getThisStruct());
				}
				inlineParent = true;
			}
			
			var res = walk0(provider, val, record, ref, pipe, inlineParent);
			if(getFlow(res) == END) end = true;
			if(shouldSave(res)) save = true;
		}
		if(save){
			field.set(null, instance, o);
		}
		return (end? END : CONTINUE)|(save? SAVE : 0);
	}
	
	private static <T extends IOInstance<T>> int walk0(
		DataProvider provider, T instance, FieldRecord record,
		Reference instanceReference, StructPipe<T> instancePipe, boolean inlineParent
	) throws IOException{
		if(instance == null) return CONTINUE;
		
		var struct = instance.getThisStruct();
		
		var fs = struct.getRealFields();
		
		boolean saveParent   = false;
		boolean dynamicPhase = false;
		long    offset       = 0;
		
		for(Iterator<IOField<T, ?>> iterator = fs.iterator(); ; ){
			var     f    = iterator.next();
			var     res  = record.log(instance, f);
			boolean save = shouldSave(res);
			
			if(getFlow(res) == CONTINUE){
				if(f.typeFlag(IOField.IOINSTANCE_FLAG)){
					res = walkField(provider, instance, record, instancePipe, instanceReference, offset, f);
				}else if(f.typeFlag(IOField.DYNAMIC_FLAG)){
					boolean inst = IOInstance.isInstance(f.getType());
					if(!inst){
						var val = f.get(null, instance);
						inst = val instanceof IOInstance ||
						       val instanceof IOInstance<?>[] ||
						       (val instanceof List<?> l && l.stream().anyMatch(e -> e instanceof IOInstance));
					}
					if(inst){
						res = walkField(provider, instance, record, instancePipe, instanceReference, offset, f);
					}
				}
			}
			
			if(save || shouldSave(res)){
				if(inlineParent){
					saveParent = true;
				}else{
					instanceReference.write(provider, false, instancePipe, instance);
				}
			}
			
			switch(getFlow(res)){
				case CONTINUE -> { }
				case END -> { return END|(saveParent? SAVE : 0); }
				default -> throw failFlow(res);
			}
			
			if(!iterator.hasNext()){
				if(dynamicPhase || !(instance instanceof IOInstance.Unmanaged<?> unmanaged)){
					return CONTINUE|(saveParent? SAVE : 0);
				}
				dynamicPhase = true;
				//noinspection unchecked
				iterator = (Iterator<IOField<T, ?>>)(Object)unmanaged.listUnmanagedFields().iterator();
				if(!iterator.hasNext()){
					return CONTINUE|(saveParent? SAVE : 0);
				}
			}
			
			VarPool<T> pool = null;
			if(f.needsIOPool()){
				pool = struct.allocVirtualVarPool(StoragePool.IO);
				if(instancePipe != null){
					instancePipe.getSizeDescriptor().calcUnknown(pool, provider, instance, WordSpace.BYTE);
				}
			}
			offset += f.getSizeDescriptor().calcUnknown(pool, provider, instance, WordSpace.BYTE);
		}
	}
}
