package com.lapissea.dfs.io.instancepipe;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.exceptions.FixedFormatNotSupported;
import com.lapissea.dfs.exceptions.IllegalField;
import com.lapissea.dfs.exceptions.MalformedStruct;
import com.lapissea.dfs.exceptions.UnsupportedStructLayout;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.objects.NumberSize;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.WordSpace;
import com.lapissea.dfs.type.field.FieldSet;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.IOFieldTools;
import com.lapissea.dfs.type.field.SizeDescriptor;
import com.lapissea.dfs.utils.iterableplus.IterablePP;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import static java.util.function.Predicate.not;

public abstract class BaseFixedStructPipe<T extends IOInstance<T>> extends StructPipe<T>{
	
	public <E extends Exception> BaseFixedStructPipe(Struct<T> type, PipeFieldCompiler<T, E> compiler, boolean initNow) throws E{
		super(type, compiler, initNow);
	}
	
	
	protected static <T extends IOInstance<T>> List<IOField<T, ?>> fixedFields(Struct<T> type, FieldSet<T> structFields, Predicate<IOField<T, ?>> checkFixed, UnaryOperator<IOField<T, ?>> makeFixed){
		type.waitForState(Struct.STATE_INIT_FIELDS);
		try{
			return IOFieldTools.stepFinal(
				structFields,
				List.of(
					IOFieldTools.streamStep(s -> s.mapIfNot(checkFixed, makeFixed)),
					IOFieldTools::dependencyReorder,
					IOFieldTools.streamStep(s -> s.filter(not(checkFixed))),
					IOFieldTools::mergeBitSpace
				));
		}catch(FixedFormatNotSupported e){
			throw new UnsupportedStructLayout(type.getFullName() + " does not support fixed size layout because of " + e.getFieldName(), e);
		}
	}
	
	protected Map<IOField<T, NumberSize>, NumberSize> computeMaxValues(FieldSet<T> structFields){
		sizeFieldStream(structFields).filter(IOField::hasDependencies).joinAsOptionalStr(", ").ifPresent(badFields -> {
			throw new IllegalField("fmt", "{}#red should not have dependencies", badFields);
		});
		
		return sizeFieldStream(structFields)
			       .distinct()
			       .toMap(Function.identity(), sizingField -> {
				       return getType().getFields().iterDependentOn(sizingField)
				                       .mapToLong(v -> v.sizeDescriptorSafe().requireMax(WordSpace.BYTE))
				                       .distinct()
				                       .mapToObj(l -> NumberSize.FLAG_INFO.firstMatching(s -> s.bytes == l).orElseThrow())
				                       .reduce((a, b) -> {
					                       if(a != b){
						                       throw new MalformedStruct("fmt", "inconsistent dependency sizes for {}#red", sizingField);
					                       }
					                       return a;
				                       })
				                       .orElse(NumberSize.LARGEST);
			       });
	}
	
	protected static <T extends IOInstance<T>> IterablePP<IOField<T, NumberSize>> sizeFieldStream(FieldSet<T> structFields){
		return structFields.flatOptionals(f -> IOFieldTools.getDynamicSize(f.getAccessor()));
	}
	
	public <E extends IOInstance<E>> SizeDescriptor.Fixed<E> getFixedDescriptor(){
		return (SizeDescriptor.Fixed<E>)super.getSizeDescriptor();
	}
	
	@Override
	public void skip(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
		src.skipExact(getFixedDescriptor().get());
	}
}
