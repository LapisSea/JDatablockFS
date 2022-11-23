package com.lapissea.cfs.query;

import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public sealed interface QueryCheck{
	
	interface SourceContain{
		Stream<QueryValueSource> sources();
		default Stream<QueryValueSource> deepSources(){
			return sources().flatMap(QueryValueSource::deep);
		}
	}
	
	default Stream<QueryCheck> deep(){
		return Stream.concat(Stream.of(this), innerValues());
	}
	default Stream<QueryCheck> innerValues(){
		return Stream.empty();
	}
	default QueryCheck negate(){
		return new Not(this);
	}
	
	default Set<String> fieldNames(){
		return deep().filter(QueryCheck.SourceContain.class::isInstance).map(QueryCheck.SourceContain.class::cast)
		             .flatMap(QueryCheck.SourceContain::deepSources)
		             .filter(QueryValueSource.Field.class::isInstance).map(QueryValueSource.Field.class::cast)
		             .map(QueryValueSource.Field::name)
		             .collect(Collectors.toUnmodifiableSet());
	}
	
	record And(QueryCheck l, QueryCheck r) implements QueryCheck{
		@Override
		public String toString(){
			return "("+l+" && "+r+")";
		}
		@Override
		public Stream<QueryCheck> innerValues(){
			return Stream.concat(l.deep(), r.deep());
		}
	}
	
	record Or(QueryCheck l, QueryCheck r) implements QueryCheck{
		@Override
		public String toString(){
			return "("+l+" || "+r+")";
		}
		@Override
		public Stream<QueryCheck> innerValues(){
			return Stream.concat(l.deep(), r.deep());
		}
	}
	
	record Not(QueryCheck check) implements QueryCheck{
		@Override
		public String toString(){
			return "!("+check+")";
		}
		@Override
		public Stream<QueryCheck> innerValues(){
			return check.deep();
		}
		@Override
		public QueryCheck negate(){
			return check;
		}
	}
	
	record Equals(QueryValueSource l, QueryValueSource r) implements QueryCheck, SourceContain{
		@Override
		public String toString(){
			return l+" == "+r;
		}
		@Override
		public Stream<QueryValueSource> sources(){
			return Stream.of(l, r);
		}
	}
	
	record GreaterThan(QueryValueSource field, QueryValueSource arg) implements QueryCheck, SourceContain{
		@Override
		public String toString(){
			return field+" > "+arg;
		}
		@Override
		public Stream<QueryValueSource> sources(){
			return Stream.of(field, arg);
		}
	}
	
	record LessThan(QueryValueSource field, QueryValueSource arg) implements QueryCheck, SourceContain{
		@Override
		public String toString(){
			return field+" < "+arg;
		}
		@Override
		public Stream<QueryValueSource> sources(){
			return Stream.of(field, arg);
		}
	}
	
	record In(QueryValueSource needle, QueryValueSource hay) implements QueryCheck, SourceContain{
		@Override
		public String toString(){
			return "("+needle+" in "+hay+")";
		}
		@Override
		public Stream<QueryValueSource> sources(){
			return Stream.of(needle, hay);
		}
	}
	
	record Lambda(Predicate<Object> lambda, Set<String> fieldNames) implements QueryCheck{
		@Override
		public String toString(){
			return "(obj->{java})";
		}
	}
	
	static QueryCheck cached(QueryCheck check){
		if(check instanceof CachedMetadata||check instanceof Lambda) return check;
		return new CachedMetadata(check);
	}
	static QueryCheck uncache(QueryCheck check){
		if(check instanceof CachedMetadata c) return c.check;
		return check;
	}
	
	final class CachedMetadata implements QueryCheck{
		private final QueryCheck  check;
		private       Set<String> fieldNames;
		
		public CachedMetadata(QueryCheck check){
			this.check=check;
		}
		
		@Override
		public String toString(){
			return check.toString();
		}
		
		public QueryCheck check(){return check;}
		@Override
		public Set<String> fieldNames(){
			if(fieldNames==null) fieldNames=check.fieldNames();
			return fieldNames;
		}
		@Override
		public boolean equals(Object obj){
			return obj==this||
			       obj instanceof CachedMetadata that&&
			       Objects.equals(this.fieldNames(), that.fieldNames())&&
			       Objects.equals(this.check, that.check);
		}
		@Override
		public int hashCode(){
			return Objects.hash(check, fieldNames);
		}
		
	}
}
