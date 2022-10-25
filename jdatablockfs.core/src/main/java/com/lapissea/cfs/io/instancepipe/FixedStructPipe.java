package com.lapissea.cfs.io.instancepipe;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.exceptions.FixedFormatNotSupportedException;
import com.lapissea.cfs.exceptions.IllegalField;
import com.lapissea.cfs.exceptions.MalformedStruct;
import com.lapissea.cfs.exceptions.UnsupportedStructLayout;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.type.*;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.SizeDescriptor;

import java.io.IOException;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.lapissea.cfs.GlobalConfig.DEBUG_VALIDATION;
import static java.util.function.Predicate.not;

public class FixedStructPipe<T extends IOInstance<T>> extends StructPipe<T>{
	
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
	
	@SuppressWarnings("unchecked")
	public static <T extends IOInstance<T>, P extends FixedStructPipe<T>> void registerSpecialImpl(Struct<T> struct, Supplier<P> newType){
		StructPipe.registerSpecialImpl(struct, (Class<P>)(Object)FixedStructPipe.class, newType);
	}
	
	private final Map<IOField<T, NumberSize>, NumberSize> maxValues;
	
	public FixedStructPipe(Struct<T> type, boolean initNow){
		super(type, initNow);
		
		maxValues=Utils.nullIfEmpty(computeMaxValues());
		
		if(DEBUG_VALIDATION){
			if(!(type instanceof Struct.Unmanaged)){
				if(!getSizeDescriptor().hasFixed()) throw new RuntimeException();
			}
		}
	}
	@Override
	protected List<IOField<T, ?>> initFields(){
		var sizeFields=sizeFieldStream().collect(Collectors.toSet());
		try{
			var type=getType();
			type.waitForState(Struct.STATE_INIT_FIELDS);
			return IOFieldTools.stepFinal(
				type.getFields(),
				List.of(
					IOFieldTools.streamStep(s->s.map(f->sizeFields.contains(f)?f:f.forceMaxAsFixedSize())),
					IOFieldTools::dependencyReorder,
					IOFieldTools.streamStep(s->s.filter(not(sizeFields::contains))),
					IOFieldTools::mergeBitSpace
				));
		}catch(FixedFormatNotSupportedException e){
			throw new UnsupportedStructLayout(getType().getType().getName()+" does not support fixed size layout because of "+e.getField(), e);
		}
	}
	private Map<IOField<T, NumberSize>, NumberSize> computeMaxValues(){
		var badFields=sizeFieldStream().filter(IOField::hasDependencies).map(IOField::toString).collect(Collectors.joining(", "));
		if(!badFields.isEmpty()){
			throw new IllegalField(badFields+" should not have dependencies");
		}
		
		return sizeFieldStream()
			       .map(sizingField->{
				       var size=getType().getFields().streamDependentOn(sizingField)
				                         .mapToLong(v->{
					                         v.declaringStruct().waitForState(Struct.STATE_INIT_FIELDS);
					                         return v.getSizeDescriptor().requireMax(WordSpace.BYTE);
				                         })
				                         .distinct()
				                         .mapToObj(l->NumberSize.FLAG_INFO.stream()
				                                                          .filter(s->s.bytes==l)
				                                                          .findAny().orElseThrow())
				                         .reduce((a, b)->{
					                         if(a!=b){
						                         throw new MalformedStruct("inconsistent dependency sizes"+sizingField);
					                         }
					                         return a;
				                         })
				                         .orElse(NumberSize.LARGEST);
				       return new AbstractMap.SimpleEntry<>(sizingField, size);
			       })
			       .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}
	
	private Stream<IOField<T, NumberSize>> sizeFieldStream(){
		return getType().getFields().stream().map(f->IOFieldTools.getDynamicSize(f.getAccessor())).filter(Optional::isPresent).map(Optional::get);
	}
	
	public <E extends IOInstance<E>> SizeDescriptor.Fixed<E> getFixedDescriptor(){
		return (SizeDescriptor.Fixed<E>)super.getSizeDescriptor();
	}
	
	private void setMax(T instance, VarPool<T> ioPool){
		maxValues.forEach((k, v)->k.set(ioPool, instance, v));
	}
	
	@Override
	protected void doWrite(DataProvider provider, ContentWriter dest, VarPool<T> ioPool, T instance) throws IOException{
		if(maxValues!=null) setMax(instance, ioPool);
		writeIOFields(getSpecificFields(), ioPool, provider, dest, instance);
	}
	
	@Override
	protected T doRead(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		if(maxValues!=null) setMax(instance, ioPool);
		readIOFields(getSpecificFields(), ioPool, provider, src, instance, genericContext);
		return instance;
	}
	
	@Override
	public void skip(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
		src.skipExact(getFixedDescriptor().get());
	}
}
