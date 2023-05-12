package com.lapissea.cfs.io.instancepipe;

import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.internal.MemPrimitive;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.VarPool;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.cfs.type.field.FieldSet;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static com.lapissea.cfs.type.field.StoragePool.IO;

public class StandardStructPipe<T extends IOInstance<T>> extends StructPipe<T>{
	
	public static <T extends IOInstance<T>> PipeFieldCompiler<T, RuntimeException> compiler(){
		return (t, structFields) -> IOFieldTools.stepFinal(structFields, List.of(
			IOFieldTools::dependencyReorder,
			IOFieldTools::mergeBitSpace
		));
	}
	
	public static <T extends IOInstance<T>> long sizeOfUnknown(DataProvider provider, T instance, WordSpace wordSpace){
		var pip = StandardStructPipe.of(instance.getThisStruct());
		return pip.calcUnknownSize(provider, instance, wordSpace);
	}
	
	public static <T extends IOInstance<T>> StandardStructPipe<T> of(Class<T> type){
		return of(Struct.of(type));
	}
	public static <T extends IOInstance<T>> StandardStructPipe<T> of(Class<T> type, int minRequestedStage){
		return of(Struct.of(type), minRequestedStage);
	}
	public static <T extends IOInstance<T>> StandardStructPipe<T> of(Struct<T> struct){
		return of(StandardStructPipe.class, struct);
	}
	public static <T extends IOInstance<T>> StandardStructPipe<T> of(Struct<T> struct, int minRequestedStage){
		return of(StandardStructPipe.class, struct, minRequestedStage);
	}
	
	@SuppressWarnings("unchecked")
	public static <T extends IOInstance<T>, P extends StandardStructPipe<T>> void registerSpecialImpl(Struct<T> struct, Supplier<P> newType){
		StructPipe.registerSpecialImpl(struct, (Class<P>)(Object)StandardStructPipe.class, newType);
	}
	
	protected StandardStructPipe(Struct<T> type, PipeFieldCompiler<T, RuntimeException> compiler, boolean initNow){
		super(type, compiler, initNow);
	}
	public StandardStructPipe(Struct<T> type, boolean initNow){
		super(type, compiler(), initNow);
	}
	
	@Override
	protected void doWrite(DataProvider provider, ContentWriter dest, VarPool<T> ioPool, T instance) throws IOException{
		writeIOFields(getSpecificFields(), ioPool, provider, dest, instance);
	}
	
	@Override
	protected T doRead(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		readIOFields(getSpecificFields(), ioPool, provider, src, instance, genericContext);
		return instance;
	}
	
	private static final int SKIP_FIXED = 0;
	private static final int SKIP       = 1;
	private static final int READ       = 2;
	
	private record SkipData<T extends IOInstance<T>>(FieldSet<T> fields, byte[] cmds, boolean needsInstance, boolean needsPool){ }
	
	private SkipData<T> skipCache;
	
	private SkipData<T> skipData(){
		var s = skipCache;
		if(s == null) skipCache = s = calcSkip();
		return s;
	}
	
	private SkipData<T> calcSkip(){
		interface CmdBuild{
			boolean needsPool();
			boolean needsInstance();
			int siz();
			void write(int off, byte[] dest);
			
			record Skip() implements CmdBuild{
				@Override
				public boolean needsPool(){ return true; }
				@Override
				public boolean needsInstance(){ return false; }
				@Override
				public int siz(){ return 1; }
				@Override
				public void write(int off, byte[] dest){
					dest[off] = SKIP;
				}
			}
			
			record SkipFixed(long bytes) implements CmdBuild{
				@Override
				public boolean needsPool(){ return false; }
				@Override
				public boolean needsInstance(){ return false; }
				@Override
				public int siz(){ return 9; }
				@Override
				public void write(int off, byte[] dest){
					dest[off] = SKIP_FIXED;
					MemPrimitive.setLong(dest, off + 1, bytes);
				}
			}
			
			record Read(boolean needsInstance) implements CmdBuild{
				@Override
				public boolean needsPool(){ return true; }
				@Override
				public int siz(){ return 1; }
				@Override
				public void write(int off, byte[] dest){
					dest[off] = READ;
				}
			}
		}
		
		var report = createSizeReport(0);
		if(report.dynamic()) return null;
		
		FieldSet<T>    fields = report.allFields();
		List<CmdBuild> cmds   = new ArrayList<>(fields.size());
		
		for(IOField<T, ?> field : fields){
			var needsInstance = !field.streamUnpackedFields().allMatch(f -> f.isVirtual(IO));
			
			if(field.streamUnpackedFields().flatMap(fields::streamDependentOn).findAny().isPresent()){
				cmds.add(new CmdBuild.Read(needsInstance));
				continue;
			}
			
			var fixed = field.getSizeDescriptor().getFixed(WordSpace.BYTE);
			if(fixed.isPresent()){
				var siz = fixed.getAsLong();
				if(!cmds.isEmpty() && cmds.get(cmds.size() - 1) instanceof CmdBuild.SkipFixed f){
					cmds.set(cmds.size() - 1, new CmdBuild.SkipFixed(f.bytes + siz));
				}else{
					cmds.add(new CmdBuild.SkipFixed(siz));
				}
				continue;
			}
			
			cmds.add(new CmdBuild.Skip());
		}
		
		var buff = new byte[cmds.stream().mapToInt(CmdBuild::siz).sum()];
		var pos  = 0;
		for(var c : cmds){
			c.write(pos, buff);
			pos += c.siz();
		}
		
		boolean needsInstance = cmds.stream().anyMatch(CmdBuild::needsInstance);
		boolean needsPool     = cmds.stream().anyMatch(CmdBuild::needsPool) && makeIOPool() != null;
		
		return new SkipData<>(fields, buff, needsInstance, needsPool);
	}
	
	@Override
	public void skip(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
		var skip = skipData();
		if(skip == null){
			readNew(provider, src, genericContext);
			return;
		}
		
		var pool = skip.needsPool? makeIOPool() : null;
		var inst = skip.needsInstance? getType().make() : null;
		
		var cmds = skip.cmds;
		for(int f = 0, c = 0; c<cmds.length; f++){
			switch(cmds[c++]){
				case SKIP_FIXED -> src.skipExact(MemPrimitive.getLong(cmds, (c += 8) - 8));
				case READ -> skip.fields.get(f).read(pool, provider, src, inst, genericContext);
				case SKIP -> skip.fields.get(f).skip(pool, provider, src, inst, genericContext);
				default -> throw new IllegalStateException();
			}
		}
	}
}
