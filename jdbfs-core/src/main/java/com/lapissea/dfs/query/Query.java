package com.lapissea.dfs.query;

import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.utils.iterableplus.Match;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;

public interface Query<T>{
	
	default List<T> allToList() throws IOException{
		var res = new ArrayList<T>();
		try(var data = open(new QueryFields())){
			while(data.step()){
				var full = data.fullEntry();
				res.add(full);
			}
		}
		return res;
	}
	
	default Match<T> firstM() throws IOException{ return Match.from(first()); }
	default Optional<T> first() throws IOException{
		try(var data = open(new QueryFields())){
			while(data.step()){
				var full = data.fullEntry();
				if(full == null) continue;
				return Optional.of(full);
			}
		}
		return Optional.empty();
	}
	default T firstNullable() throws IOException{
		try(var data = open(new QueryFields())){
			if(data.step()){
				return data.fullEntry();
			}
		}
		return null;
	}
	
	default long count() throws IOException{
		long count = 0;
		try(var data = open(new QueryFields())){
			while(data.step()){
				count++;
			}
		}
		return count;
	}
	
	interface Test<T>{
		interface RefF<T extends IOInstance<T>, V> extends Test<T>{
			@Override
			default Iterable<IOField<?, ?>> reportFields(){
				return List.of(ref());
			}
			IOField<T, V> ref();
			
			@Override
			default boolean test(T element){
				var val = ref().get(null, element);
				return fieldTest(val);
			}
			boolean fieldTest(V field);
		}
		
		record Field<T extends IOInstance<T>, V>(IOField<T, V> ref, Predicate<V> match) implements RefF<T, V>{
			@Override
			public boolean fieldTest(V field){
				return match.test(field);
			}
		}
		
		record Fields<T extends IOInstance<T>>(List<IOField<T, ?>> refs, Predicate<T> match) implements Test<T>{
			@Override
			public Iterable<IOField<?, ?>> reportFields(){
				return List.copyOf(refs);
			}
			@Override
			public boolean test(T element){
				return match.test(element);
			}
		}
		
		record FieldIs<T extends IOInstance<T>, V>(IOField<T, V> ref, V needle, boolean forTrue) implements RefF<T, V>{
			@Override
			public boolean fieldTest(V field){
				return Objects.equals(field, needle) == forTrue;
			}
		}
		
		record FieldCompare<T extends IOInstance<T>, V extends Comparable<V>>(
			IOField<T, V> ref, V check, boolean greater, boolean equal
		) implements RefF<T, V>{
			@Override
			public boolean fieldTest(V field){
				var res = field.compareTo(check);
				if(res == 0 && equal) return true;
				return (res>0) == greater;
			}
		}
		
		static <T extends IOInstance<T>, V> Test<T> field(Struct.FieldRef<T, V> ref, Predicate<V> match){
			return new Field<>(QuerySupport.asIOField(ref), match);
		}
		static <T extends IOInstance<T>, V1, V2> Test<T> fields(Struct.FieldRef<T, V1> ref1, Struct.FieldRef<T, V2> ref2, BiPredicate<V1, V2> match){
			var f1 = QuerySupport.asIOField(ref1);
			var f2 = QuerySupport.asIOField(ref2);
			return new Fields<>(List.of(f1, f2), el -> {
				var v1 = f1.get(null, el);
				var v2 = f2.get(null, el);
				return match.test(v1, v2);
			});
		}
		static <T extends IOInstance<T>> Test<T> fields(List<Struct.FieldRef<T, ?>> refs, Predicate<T> match){
			List<IOField<T, ?>> fields = new ArrayList<>(refs.size());
			for(Struct.FieldRef<T, ?> ref : refs){
				fields.add(QuerySupport.asIOField(ref));
			}
			return new Fields<>(List.copyOf(fields), match);
		}
		static <T extends IOInstance<T>, V> Test<T> fieldMatch(Struct.FieldRef<T, V> ref1, Struct.FieldRef<T, V> ref2){
			return fields(ref1, ref2, Objects::equals);
		}
		static <T extends IOInstance<T>, V> Test<T> fieldNull(Struct.FieldRef<T, V> ref){
			return new FieldIs<>(QuerySupport.asIOField(ref), null, true);
		}
		static <T extends IOInstance<T>, V> Test<T> fieldNonNull(Struct.FieldRef<T, V> ref){
			return new FieldIs<>(QuerySupport.asIOField(ref), null, false);
		}
		static <T extends IOInstance<T>, V> Test<T> fieldEQ(Struct.FieldRef<T, V> ref, V needle){
			return new FieldIs<>(QuerySupport.asIOField(ref), needle, true);
		}
		static <T extends IOInstance<T>, V> Test<T> fieldNotEQ(Struct.FieldRef<T, V> ref, V needle){
			return new FieldIs<>(QuerySupport.asIOField(ref), needle, false);
		}
		static <T extends IOInstance<T>, V extends Comparable<V>> Test<T> fieldGr(Struct.FieldRef<T, V> ref, V needle){
			return new FieldCompare<>(QuerySupport.asIOField(ref), needle, true, false);
		}
		static <T extends IOInstance<T>, V extends Comparable<V>> Test<T> fieldGrEq(Struct.FieldRef<T, V> ref, V needle){
			return new FieldCompare<>(QuerySupport.asIOField(ref), needle, true, true);
		}
		static <T extends IOInstance<T>, V extends Comparable<V>> Test<T> fieldLe(Struct.FieldRef<T, V> ref, V needle){
			return new FieldCompare<>(QuerySupport.asIOField(ref), needle, false, false);
		}
		static <T extends IOInstance<T>, V extends Comparable<V>> Test<T> fieldLeEq(Struct.FieldRef<T, V> ref, V needle){
			return new FieldCompare<>(QuerySupport.asIOField(ref), needle, false, true);
		}
		
		Iterable<IOField<?, ?>> reportFields();
		boolean test(T element);
	}
	
	default Query<T> where(Test<T> test)                               { return where(List.of(test)); }
	default Query<T> where(Test<T> test1, Test<T> test2)               { return where(List.of(test1, test2)); }
	default Query<T> where(Test<T> test1, Test<T> test2, Test<T> test3){ return where(List.of(test1, test2, test3)); }
	default Query<T> where(List<Test<T>> tests){
		return new Queries.Filtered<>(this, tests);
	}
	
	/**
	 * Filters the query results by an arbitrary lambda.
	 * This will require the entire element.
	 * If possible, use {@link Query#where}.
	 *
	 * @param match the function to filter the query contents by
	 */
	default Query<T> filterFull(Predicate<T> match){
		return new Queries.FilterGeneric<>(this, match);
	}
	
	/**
	 * Maps the query results by a specific field.
	 * Using this over {@link Query#mapFull} has the benefit of the query being aware you need only this field.
	 * It will not read any irrelevant fields.
	 *
	 * @param ref reference to the field. Eg: <code>Foobar::foo</code>
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	default <R> Query<R> mapF(Struct.FieldRef<T, R> ref){
		var field = QuerySupport.asIOField((Struct.FieldRef)ref);
		return new Queries.FieldMapped(this, field);
	}
	
	/**
	 * Maps the query results by an arbitrary lambda.
	 * This will require the entire element.
	 * If possible, use {@link Query#mapF}.
	 *
	 * @param mapper the function to transform the query contents by
	 */
	default <R> Query<R> mapFull(Function<T, R> mapper){
		return new Queries.Mapped<>(this, mapper);
	}
	
	QueryableData.QuerySource<T> open(QueryFields queryFields) throws IOException;
}
