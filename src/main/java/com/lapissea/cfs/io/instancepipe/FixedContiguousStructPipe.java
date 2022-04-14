package com.lapissea.cfs.io.instancepipe;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.exceptions.FixedFormatNotSupportedException;
import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.util.NotImplementedException;

import java.io.IOException;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.lapissea.cfs.GlobalConfig.DEBUG_VALIDATION;
import static com.lapissea.cfs.type.field.IOField.UsageHintType.SIZE_DATA;
import static java.util.function.Predicate.not;

public class FixedContiguousStructPipe<T extends IOInstance<T>> extends StructPipe<T>{
	
	public static <T extends IOInstance<T>> FixedContiguousStructPipe<T> of(Class<T> type){
		return of(Struct.of(type));
	}
	public static <T extends IOInstance<T>> FixedContiguousStructPipe<T> of(Struct<T> struct){
		return of(FixedContiguousStructPipe.class, struct);
	}
	
	@SuppressWarnings("unchecked")
	public static <T extends IOInstance<T>, P extends FixedContiguousStructPipe<T>> void registerSpecialImpl(Struct<T> struct, Supplier<P> newType){
		StructPipe.registerSpecialImpl(struct, (Class<P>)(Object)FixedContiguousStructPipe.class, newType);
	}
	
	private final Map<IOField<T, NumberSize>, NumberSize> maxValues;
	
	public FixedContiguousStructPipe(Struct<T> type){
		super(type);
		
		maxValues=Utils.nullIfEmpty(computeMaxValues());
		
		if(DEBUG_VALIDATION){
			if(!(type instanceof Struct.Unmanaged)){
				getSizeDescriptor().requireFixed(WordSpace.BYTE);
			}
		}
	}
	@Override
	protected List<IOField<T, ?>> initFields(){
		var sizeFields=sizeFieldStream().collect(Collectors.toSet());
		try{
			return IOFieldTools.stepFinal(
				getType().getFields(),
				List.of(
					IOFieldTools.streamStep(s->s.map(f->sizeFields.contains(f)?f:f.forceMaxAsFixedSize())),
					IOFieldTools::dependencyReorder,
					IOFieldTools.streamStep(s->s.filter(not(sizeFields::contains))),
					IOFieldTools::mergeBitSpace
				));
		}catch(FixedFormatNotSupportedException e){
			throw new MalformedStructLayout(getType().getType().getName()+" does not support fixed size layout because of "+e.getField(), e);
		}
	}
	private Map<IOField<T, NumberSize>, NumberSize> computeMaxValues(){
		//TODO: see how to properly handle max values with dependencies
		var badFields=sizeFieldStream().filter(IOField::hasDependencies).map(IOField::toString).collect(Collectors.joining(", "));
		if(!badFields.isEmpty()){
			throw new NotImplementedException(badFields+" should not have dependencies");
		}
		
		return sizeFieldStream()
			       .map(sizingField->{
				       var size=getType().getFields().streamDependentOn(sizingField)
				                         .mapToLong(v->v.getSizeDescriptor().requireMax(WordSpace.BYTE))
				                         .distinct()
				                         .mapToObj(l->NumberSize.FLAG_INFO.stream()
				                                                          .filter(s->s.bytes==l)
				                                                          .findAny().orElseThrow())
				                         .reduce((a, b)->{
					                         if(a!=b){
						                         throw new MalformedStructLayout("inconsistent dependency sizes"+sizingField);
					                         }
					                         return a;
				                         })
				                         .orElse(NumberSize.LARGEST);
				       return new AbstractMap.SimpleEntry<>(sizingField, size);
			       })
			       .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}
	
	private Stream<IOField<T, NumberSize>> sizeFieldStream(){
		return getType().getFields().byType(NumberSize.class).filter(f->f.hasUsageHint(SIZE_DATA));
	}
	
	public <E extends IOInstance<E>> SizeDescriptor.Fixed<E> getFixedDescriptor(){
		return (SizeDescriptor.Fixed<E>)super.getSizeDescriptor();
	}
	
	private void setMax(T instance, Struct.Pool<T> ioPool){
		if(maxValues==null) return;
		maxValues.forEach((k, v)->k.set(ioPool, instance, v));
	}
	
	@Override
	protected void doWrite(DataProvider provider, ContentWriter dest, T instance) throws IOException{
		var ioPool=makeIOPool();
		setMax(instance, ioPool);
		writeIOFields(getSpecificFields(), ioPool, provider, dest, instance);
	}
	
	@Override
	protected T doRead(Struct.Pool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		setMax(instance, ioPool);
		readIOFields(getSpecificFields(), ioPool, provider, src, instance, genericContext);
		return instance;
	}
}
