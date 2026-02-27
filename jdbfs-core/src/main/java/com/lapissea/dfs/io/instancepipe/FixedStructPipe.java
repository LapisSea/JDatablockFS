package com.lapissea.dfs.io.instancepipe;

import com.lapissea.dfs.Utils;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.objects.NumberSize;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.field.FieldSet;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.SpecializedGenerator;
import com.lapissea.iterableplus.Match;

import java.io.IOException;
import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Map;

import static com.lapissea.dfs.config.GlobalConfig.DEBUG_VALIDATION;

public class FixedStructPipe<T extends IOInstance<T>> extends BaseFixedStructPipe<T>{
	
	private static <T extends IOInstance<T>> PipeFieldCompiler.Result<T> standardCompile(Struct<T> t, FieldSet<T> structFields, boolean testRun){
		var sizeFields = sizeFieldStream(structFields).toModSet();
		var fields     = fixedFields(t, structFields, sizeFields::contains, IOField::forceMaxAsFixedSize);
		return new PipeFieldCompiler.Result<>(fields);
	}
	
	public static <T extends IOInstance<T>> FixedStructPipe<T> of(Class<T> type){
		return of(Struct.of(type));
	}
	public static <T extends IOInstance<T>> FixedStructPipe<T> of(Class<T> type, int minRequestedStage){
		return of(Struct.of(type), minRequestedStage);
	}
	public static <T extends IOInstance<T>> FixedStructPipe<T> of(Struct<T> struct){
		return of(FixedStructPipe.class, struct);
	}
	public static <T extends IOInstance<T>> FixedStructPipe<T> of(Struct<T> struct, int minRequestedStage){
		return of(FixedStructPipe.class, struct, minRequestedStage);
	}
	
	private Map<IOField<T, NumberSize>, NumberSize> maxValues;
	private boolean                                 maxValuesInited = false;
	
	public FixedStructPipe(Struct<T> type, int syncStage){
		this(type, FixedStructPipe::standardCompile, syncStage);
	}
	public FixedStructPipe(Struct<T> type, PipeFieldCompiler<T, RuntimeException> compiler, int syncStage){
		super(type, compiler, syncStage);
	}
	
	@Override
	public Class<StructPipe<T>> getSelfClass(){
		//noinspection unchecked,rawtypes
		return (Class)FixedStructPipe.class;
	}
	@Override
	protected void postValidate(){
		if(DEBUG_VALIDATION){
			if(!(getType() instanceof Struct.Unmanaged)){
				if(!getSizeDescriptor().hasFixed()){
					throw new RuntimeException("Unmanaged type not fixed");
				}
			}
		}
		super.postValidate();
	}
	
	private void setMax(T instance, VarPool<T> ioPool){
		maxValues.forEach((k, v) -> k.set(ioPool, instance, v));
	}
	private void initMax(){
		maxValuesInited = true;
		maxValues = Utils.nullIfEmpty(computeMaxValues(getType().getFields()));
	}
	
	@Override
	protected void doWrite(DataProvider provider, ContentWriter dest, VarPool<T> ioPool, T instance) throws IOException{
		if(!maxValuesInited) initMax();
		if(maxValues != null) setMax(instance, ioPool);
		writeIOFields(getSpecificFields(), ioPool, provider, dest, instance);
	}
	
	@Override
	protected T doRead(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		return genericDoRead(ioPool, provider, src, instance, genericContext);
	}
	private T genericDoRead(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		if(!maxValuesInited) initMax();
		if(maxValues != null) setMax(instance, ioPool);
		readIOFields(getSpecificFields(), ioPool, provider, src, instance, genericContext);
		return instance;
	}
	
	private static MethodHandle genericDoRead;
	private static MethodHandle genericReadNew;
	private static synchronized MethodHandle getGenericDoRead() throws IllegalAccessException, NoSuchMethodException{
		if(genericDoRead == null){
			var mt = FixedStructPipe.class.getDeclaredMethod(
				"genericDoRead",
				VarPool.class, DataProvider.class, ContentReader.class, IOInstance.class, GenericContext.class);
			genericDoRead = MethodHandles.lookup().unreflect(mt);
		}
		return genericDoRead;
	}
	private static synchronized MethodHandle getGenericReadNew() throws IllegalAccessException, NoSuchMethodException{
		if(genericReadNew == null){
			var mt = FixedStructPipe.class.getDeclaredMethod(
				"genericReadNew", DataProvider.class, ContentReader.class, GenericContext.class);
			genericReadNew = MethodHandles.lookup().unreflect(mt);
		}
		return genericReadNew;
	}
	
	private T genericReadNew(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
		return super.readNew(provider, src, genericContext);
	}
	
	public static <T extends IOInstance<T>> CallSite bootstrapDoRead(MethodHandles.Lookup lookup, String name, MethodType ignore, Class<T> objType) throws Throwable{
		var fields = makeSTDFields(objType);
		return PipeCodeGen.boostrapDoReadFromFields(lookup, name, objType, fields, FixedStructPipe::getGenericDoRead);
	}
	
	public static <T extends IOInstance<T>> CallSite bootstrapReadNew(MethodHandles.Lookup lookup, String name, MethodType ignore, Class<T> objType) throws Throwable{
		var fields = makeSTDFields(objType);
		return PipeCodeGen.boostrapReadNewFromFields(lookup, name, objType, fields, FixedStructPipe::getGenericReadNew);
	}
	
	@Override
	protected Match<PipeCodeGen.PipeWriter<T>> getSpecializedImplementationWriter(){
		return Match.of((writer, constants, type) -> {
			PipeCodeGen.defaultClassDef(writer);
			
			boolean hasReadyStruct = getType().getInitializationState()>=StructPipe.STATE_IO_FIELD;
			constants.add(new SpecializedGenerator.AccessMap.ConstantRequest.DebugField(boolean.class, "DEBUG_READY_READ", hasReadyStruct + ""));
			
			List<SpecializedGenerator> generators;
			if(hasReadyStruct){
				List<IOField<T, ?>> fields;
				if(getInitializationState()>=STATE_IO_FIELD) fields = getSpecificFields();
				else fields = makeSTDFields(type);
				generators = PipeCodeGen.tryGetSpecializedGenerators(type, fields);
			}else generators = null;
			
			var accessMap = new SpecializedGenerator.AccessMap();
			PipeCodeGen.writeConstants(writer, constants, accessMap);
			
			boolean noCtor = type.isAnnotationPresent(Struct.NoDefaultConstructor.class);
			
			PipeCodeGen.overwrite_doRead(writer, noCtor? generators : null, accessMap);
			
			if(!noCtor){
				PipeCodeGen.overwrite_readNew(writer, generators, accessMap, getType());
			}
			
			writer.wEnd();
		});
	}
	private static <T extends IOInstance<T>> List<IOField<T, ?>> makeSTDFields(Class<T> type){
		var struct = Struct.of(type, Struct.STATE_FIELD_MAKE);
		return standardCompile(struct, struct.getFields(), false).fields();
	}
	
}
