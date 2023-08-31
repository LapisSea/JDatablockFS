package com.lapissea.cfs.io.instancepipe;

import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.exceptions.FixedFormatNotSupported;
import com.lapissea.cfs.exceptions.IllegalField;
import com.lapissea.cfs.exceptions.MalformedStruct;
import com.lapissea.cfs.exceptions.UnsupportedStructLayout;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.cfs.type.field.FieldSet;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.SizeDescriptor;

import java.io.IOException;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.function.Predicate.not;

public abstract class BaseFixedStructPipe<T extends IOInstance<T>> extends StructPipe<T>{
	
	public <E extends Exception> BaseFixedStructPipe(Struct<T> type, PipeFieldCompiler<T, E> compiler, boolean initNow) throws E{
		super(type, compiler, initNow);
	}
	
	
	protected static <T extends IOInstance<T>> List<IOField<T, ?>> fixedFields(Struct<T> type, FieldSet<T> structFields, Predicate<IOField<T, ?>> checkFixed, Function<IOField<T, ?>, IOField<T, ?>> makeFixed){
		type.waitForState(Struct.STATE_INIT_FIELDS);
		try{
			return IOFieldTools.stepFinal(
				structFields,
				List.of(
					IOFieldTools.streamStep(s -> s.map(f -> checkFixed.test(f)? f : makeFixed.apply(f))),
					IOFieldTools::dependencyReorder,
					IOFieldTools.streamStep(s -> s.filter(not(checkFixed))),
					IOFieldTools::mergeBitSpace
				));
		}catch(FixedFormatNotSupported e){
			throw new UnsupportedStructLayout(type.getFullName() + " does not support fixed size layout because of " + e.getField(), e);
		}
	}
	
	protected Map<IOField<T, NumberSize>, NumberSize> computeMaxValues(FieldSet<T> structFields){
		var badFields = sizeFieldStream(structFields).filter(IOField::hasDependencies).map(IOField::toString).collect(Collectors.joining(", "));
		if(!badFields.isEmpty()){
			throw new IllegalField(badFields + " should not have dependencies");
		}
		
		return sizeFieldStream(structFields)
			       .map(sizingField -> {
				       var size = getType().getFields().streamDependentOn(sizingField)
				                           .mapToLong(v -> v.sizeDescriptorSafe().requireMax(WordSpace.BYTE))
				                           .distinct()
				                           .mapToObj(l -> NumberSize.FLAG_INFO.filtered(s -> s.bytes == l)
				                                                              .first().orElseThrow())
				                           .reduce((a, b) -> {
					                           if(a != b){
						                           throw new MalformedStruct("inconsistent dependency sizes" + sizingField);
					                           }
					                           return a;
				                           })
				                           .orElse(NumberSize.LARGEST);
				       return new AbstractMap.SimpleEntry<>(sizingField, size);
			       })
			       .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}
	
	protected static <T extends IOInstance<T>> Stream<IOField<T, NumberSize>> sizeFieldStream(FieldSet<T> structFields){
		return structFields.stream().map(f -> IOFieldTools.getDynamicSize(f.getAccessor())).filter(Optional::isPresent).map(Optional::get);
	}
	
	public <E extends IOInstance<E>> SizeDescriptor.Fixed<E> getFixedDescriptor(){
		return (SizeDescriptor.Fixed<E>)super.getSizeDescriptor();
	}
	
	@Override
	public void skip(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
		src.skipExact(getFixedDescriptor().get());
	}
}
