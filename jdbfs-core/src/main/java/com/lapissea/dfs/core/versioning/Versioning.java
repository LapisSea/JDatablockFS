package com.lapissea.dfs.core.versioning;

import com.lapissea.dfs.exceptions.IncompatibleVersionTransform;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.util.NotImplementedException;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Versioning{
	
	public static final Versioning JUST_FAIL = new Versioning(Set.of(), List.of());
	
	private final EnumSet<VersioningOptions>         options;
	private final Map<String, VersionTransformer<?>> transformers;
	
	public Versioning(Set<VersioningOptions> options, List<VersionTransformer<?>> transformers){
		this.options = options.isEmpty()? EnumSet.noneOf(VersioningOptions.class) : EnumSet.copyOf(options);
		this.transformers = transformers.stream().collect(Collectors.toMap(t -> t.matchingClassName, Function.identity()));
	}
	
	
	private record FieldOp(String name, FieldHandler op){ }
	
	private interface FieldHandler{
		<T extends IOInstance<T>> void apply(String name, Struct<T> realT, T realIns, UnpackedInstance oldIns);
		
		FieldHandler COPY = new FieldHandler(){
			@Override
			public <T extends IOInstance<T>> void apply(String name, Struct<T> realT, T realIns, UnpackedInstance oldIns){
				//noinspection unchecked
				var f = (IOField<T, Object>)realT.getFields().requireByName(name);
				f.set(null, realIns, oldIns.byName(name));
			}
		}, NEW_DEFAULT    = new FieldHandler(){
			@Override
			public <T extends IOInstance<T>> void apply(String name, Struct<T> realT, T realIns, UnpackedInstance oldIns){
				var f = realT.getFields().requireByName(name);
				
				if(!f.nullable() && f.isNull(null, realIns)){
					throw new IncompatibleVersionTransform(
						"Can not automatically create new field: " + f.getName() +
						" because it is non null but is not initialized by default"
					);
				}
			}
		}, REMOVE         = new FieldHandler(){
			@Override
			public <T extends IOInstance<T>> void apply(String name, Struct<T> realT, T realIns, UnpackedInstance oldIns){
				//Nothing to do, just lose the data
			}
		};
	}
	
	private <T extends IOInstance<T>> VersionTransformer<T> incompatibleTransform(ClassVersionDiff diff, String reason){
		return new VersionTransformer<>(diff.real().getName(), unpacked -> {
			var sj = new StringJoiner("\n");
			sj.add("Could not transform: " + diff.real().getName());
			sj.add("Reason: " + reason);
			if(!diff.newFields().isEmpty()) sj.add("New fields: " + diff.newFields());
			if(!diff.changedFields().isEmpty()) sj.add("Changed fields: " + diff.changedFields());
			if(!diff.removedFields().isEmpty()) sj.add("Removed fields: " + diff.removedFields());
			throw new IncompatibleVersionTransform(sj.toString());
		});
	}
	
	private <T extends IOInstance<T>> VersionTransformer<T> autoOpsTransform(Struct<T> struct, ClassVersionDiff diff){
		
		
		var excludeSet = Stream.of(diff.newFields(), diff.changedFields()).flatMap(Collection::stream).collect(Collectors.toSet());
		
		List<FieldOp> autoOps =
			struct.getRealFields()
			      .map(IOField::getName)
			      .filtered(n -> !excludeSet.contains(n))
			      .map(n -> new FieldOp(n, FieldHandler.COPY))
			      .collectToList();
		
		if(!diff.newFields().isEmpty()){
			if(options.contains(VersioningOptions.ATTEMPT_NEW_VALUE_DEFAULT)){
				for(var name : diff.newFields()){
					autoOps.add(new FieldOp(name, FieldHandler.NEW_DEFAULT));
				}
			}else{
				return incompatibleTransform(diff, "Could not handle new fields");
			}
		}
		
		if(!diff.changedFields().isEmpty()){
			if(options.contains(VersioningOptions.AUTO_COLLECTION_INTERPRET)){
				for(var name : diff.changedFields()){
					var field = struct.getFields().requireByName(name);
					if(field.typeFlag(IOField.DYNAMIC_FLAG)){
						return incompatibleTransform(diff, "Can not handle dynamic collection transform");
					}
					var type = field.getGenericType(null);
					
					return incompatibleTransform(diff, "not implemented");
				}
			}else{
				return incompatibleTransform(diff, "Could not handle changed fields");
			}
		}
		
		if(!diff.removedFields().isEmpty()){
			if(options.contains(VersioningOptions.AUTO_REMOVE)){
				for(var name : diff.removedFields()){
					autoOps.add(new FieldOp(name, FieldHandler.REMOVE));
				}
			}else{
				return incompatibleTransform(diff, "Could not handle removed fields");
			}
		}
		
		var finalOps = List.copyOf(autoOps);
		
		return new VersionTransformer<>(diff.real().getName(), unpacked -> {
			var inst = struct.make();
			for(var fieldOp : finalOps){
				fieldOp.op.apply(fieldOp.name, struct, inst, unpacked);
			}
			return inst;
		});
	}
	
	public VersionTransformer<?> createTransformer(ClassVersionDiff diff){
		var transformer = transformers.get(diff.real().getName());
		if(transformer != null) return transformer;
		
		
		var structO = Struct.tryOf(diff.real());
		if(structO.isPresent()){
			return autoOpsTransform(structO.get(), diff);
		}
		
		throw new NotImplementedException("Could not handle\n" + diff);
	}
}
