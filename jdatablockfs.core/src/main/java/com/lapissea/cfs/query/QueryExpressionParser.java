package com.lapissea.cfs.query;

import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.util.LogUtil;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.UtilL;

import java.lang.reflect.Array;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class QueryExpressionParser{
	
	private sealed interface ArgSource{
		record Root() implements ArgSource{}
		
		record GetArray(int index, ArgSource source) implements ArgSource{}
		
		record Litelar(Object value) implements ArgSource{}
	}
	
	private interface ArgContain{
		ArgSource arg();
	}
	
	private sealed interface Check{
		record And(Check l, Check r) implements Check{
			@Override
			public Stream<Check> deep(){return Stream.of(Stream.of(this), l.deep(), r.deep()).flatMap(s->s);}
		}
		
		record Or(Check l, Check r) implements Check{
			@Override
			public Stream<Check> deep(){return Stream.of(Stream.of(this), l.deep(), r.deep()).flatMap(s->s);}
		}
		
		record Not(Check check) implements Check{
			@Override
			public Stream<Check> deep(){return Stream.of(Stream.of(this), check.deep()).flatMap(s->s);}
		}
		
		record Equals(String name, ArgSource arg) implements Check, ArgContain{
			@Override
			public Stream<Check> deep(){return Stream.of(this);}
		}
		
		record GreaterThan(String name, ArgSource arg) implements Check, ArgContain{
			@Override
			public Stream<Check> deep(){return Stream.of(this);}
		}
		
		record LessThan(String name, ArgSource arg) implements Check, ArgContain{
			@Override
			public Stream<Check> deep(){return Stream.of(this);}
		}
		
		Stream<Check> deep();
	}
	
	private record FilterQuery<T>(Class<T> type, String expression, int hash){
		private FilterQuery(Class<T> type, String expression){
			this(type, expression, (31+type.hashCode())*31+expression.hashCode());
		}
		@Override
		public int hashCode(){
			return hash;
		}
	}
	
	public record FilterResult<T>(Set<String> readFields, Consumer<T> argCheck, BiPredicate<Object[], T> filter){}
	
	
	private static final Map<FilterQuery<?>, FilterResult<?>> FILTER_CACHE=new ConcurrentHashMap<>();
	
	@SuppressWarnings("unchecked")
	public static <T> FilterResult<T> filter(Class<T> type, String expression){
		return (FilterResult<T>)FILTER_CACHE.computeIfAbsent(new FilterQuery<>(type, expression), QueryExpressionParser::parse);
	}
	
	private static <T> FilterResult<T> parse(FilterQuery<T> filterQuery){
//		filterQuery.expression.chars();
		var fields=new HashSet<String>();
		
		Check check=expressionToCheck(filterQuery.expression);
		
		var args=check.deep().filter(c->c instanceof ArgContain).map(c->((ArgContain)c).arg()).collect(Collectors.toSet());
		
		Consumer<T>              argCheck =generateArgCheck(args);
		BiPredicate<Object[], T> predicate=generateFilter(args, check);
		
		return new FilterResult<>(Set.copyOf(fields), argCheck, predicate);
	}
	
	private static <T> BiPredicate<Object[], T> generateFilter(Set<ArgSource> argSet, Check check){
		return (args, obj)->reflectionCheck(args, obj, check);
	}
	
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static <T> boolean reflectionCheck(Object[] args, T obj, Check check){
		return switch(check){
			case Check.And and -> reflectionCheck(args, obj, and.l)&&reflectionCheck(args, obj, and.r);
			case Check.Equals equals -> {
				Object arg=getArg(args, equals.arg);
				if(arg==null) yield obj==null;
				IOField f=Struct.ofUnknown(obj.getClass()).getFields().byName(equals.name).orElseThrow();
				if(!UtilL.instanceOf(obj.getClass(), f.getAccessor().getType())){
					throw new ClassCastException(obj+" not compatible with "+f);
				}
				var val=f.get(null, (IOInstance)obj);
				yield arg.equals(val);
			}
			case Check.GreaterThan equals -> {
				Number  arg=(Number)getArg(args, equals.arg);
				IOField f  =Struct.ofUnknown(obj.getClass()).getFields().byName(equals.name).orElseThrow();
				Number  val=(Number)f.get(null, (IOInstance)obj);
				yield val.doubleValue()>arg.doubleValue();
			}
			case Check.LessThan equals -> {
				Number  arg=(Number)getArg(args, equals.arg);
				IOField f  =Struct.ofUnknown(obj.getClass()).getFields().byName(equals.name).orElseThrow();
				Number  val=(Number)f.get(null, (IOInstance)obj);
				yield val.doubleValue()<arg.doubleValue();
			}
			case Check.Not not -> !reflectionCheck(args, obj, not.check);
			case Check.Or or -> reflectionCheck(args, obj, or.l)||reflectionCheck(args, obj, or.r);
		};
	}
	private static Object getArg(Object[] args, ArgSource arg){
		return switch(arg){
			case ArgSource.GetArray getArray -> Array.get(getArg(args, getArray.source), getArray.index);
			case ArgSource.Root root -> args;
			case ArgSource.Litelar litelar -> litelar.value;
		};
	}
	
	private static <T> Consumer<T> generateArgCheck(Set<ArgSource> args){
		return t->{};
	}
	
	private static class Reader{
		private       int    pos;
		private final String str;
		private Reader(String str){
			this.str=str;
		}
		
		private interface Test{
			boolean test(int i, char c);
		}
		
		private String advance(Test predicate){
			var start=pos;
			for(int i=pos;i<str.length();i++){
				var c=str.charAt(i);
				if(predicate.test(i, c)){
					continue;
				}
				pos=i;
				break;
			}
			if(start==pos) return null;
			return str.substring(start, pos);
		}
		private String field(){
			var start=pos;
			return advance((i, c)->{
				if(i==start){
					return Character.isJavaIdentifierStart(c);
				}
				return Character.isJavaIdentifierPart(c);
			});
		}
		public String squiggly(){
			skipWhite();
			if(str.charAt(pos)!='{') return null;
			pos++;
			return advance((i, c)->c!='}');
		}
		public String brace(){
			skipWhite();
			if(str.charAt(pos)!='(') return null;
			pos++;  +
			
						
			int[] depth={1};
			return advance((i, c)->{
				if(c=='(') depth[0]++;
				if(c==')') depth[0]--;
				return depth[0]>0;
			});
		}
		private boolean and(){
			return match(List.of("&", "&&", "and"));
		}
		private boolean or(){
			return match(List.of("|", "||", "or"));
		}
		private boolean not(){
			return match(List.of("!", "not"));
		}
		private boolean equals(){
			return exact("==")||exact("=")||match(List.of("equals"));
		}
		private boolean greater(){
			return exact(">");
		}
		private boolean lesser(){
			return exact("<");
		}
		private boolean match(List<String> matches){
			skipWhite();
			for(var match : matches){
				if(str.substring(pos).toLowerCase().startsWith(match)){
					var c=str.charAt(pos+match.length());
					if(List.of('(', ')', '!', '&', '|').contains(c)||Character.isWhitespace(c)){
						pos+=match.length();
						return true;
					}
				}
			}
			return false;
		}
		private boolean exact(String match){
			skipWhite();
			if(str.substring(pos).toLowerCase().startsWith(match)){
				pos+=match.length();
				return true;
			}
			return false;
		}
		private void skipWhite(){
			for(int i=pos;i<str.length();i++){
				if(Character.isWhitespace(str.charAt(i))) pos++;
				else break;
			}
		}
	}
	
	private static class Parser{
		private final Reader  reader;
		private       boolean notNext=false;
		
		private Parser(Reader reader){
			this.reader=reader;
		}
		
		Check parse(){
			Check check=null;
			while(true){
				if(reader.not()){
					notNext=!notNext;
					continue;
				}
				
				if(check!=null){
					if(reader.and()){
						var r=requireNextCheck();
						check=new Check.And(check, r);
						continue;
					}
					if(reader.or()){
						var r=requireNextCheck();
						check=new Check.Or(check, r);
						continue;
					}
				}else{
					check=requireNextCheck();
					continue;
				}
				
				return check;
			}
		}
		
		private Check requireNextCheck(){
			return Objects.requireNonNull(nextCheck(), "Unexpected end");
		}
		
		private Check nextCheck(){
			var check=nextCheck0();
			if(check==null) return null;
			if(notNext){
				notNext=false;
				check=new Check.Not(check);
			}
			return check;
		}
		private Check nextCheck0(){
			var brace=reader.brace();
			if(brace!=null){
				return expressionToCheck(brace);
			}
			
			var fieldName=reader.field();
			if(fieldName!=null){
				if(reader.equals()){
					var argStr=reader.squiggly();
					if(argStr!=null){
						int index=Integer.parseInt(argStr);
						return new Check.Equals(fieldName, new ArgSource.GetArray(index, new ArgSource.Root()));
					}
					throw new NotImplementedException();
				}
				if(reader.greater()){
					var argStr=reader.squiggly();
					if(argStr!=null){
						int index=Integer.parseInt(argStr);
						return new Check.GreaterThan(fieldName, new ArgSource.GetArray(index, new ArgSource.Root()));
					}
					throw new NotImplementedException();
				}
				if(reader.lesser()){
					var argStr=reader.squiggly();
					if(argStr!=null){
						int index=Integer.parseInt(argStr);
						return new Check.LessThan(fieldName, new ArgSource.GetArray(index, new ArgSource.Root()));
					}
					throw new NotImplementedException();
				}
				LogUtil.println(fieldName);
			}
			
			throw new NotImplementedException();
		}
	}
	
	private static Check expressionToCheck(String expression){
		return new Parser(new Reader(expression)).parse();
	}
}
