package com.lapissea.cfs.query;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.exceptions.InvalidQueryString;
import com.lapissea.cfs.io.bit.EnumUniverse;
import com.lapissea.cfs.logging.Log;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.lang.Double.parseDouble;
import static java.lang.Long.parseLong;

public class QueryExpressionParser{
	
	private interface Matched{
		List<String> matches();
	}
	
	private enum Modifier implements Matched{
		MODULUS(QueryValueSource.Modulus::new, "%", "mod"),
		ADD(QueryValueSource.Add::new, "+", "add"),
//		SUBTRACT(DataSource.Subtract::new, "-", "sub"),
//		DIVIDE(DataSource.Divide::new, "/", "div"),
//		MULTIPLY(DataSource.Multiply::new, "-", "mul"),
//		POWER(DataSource.Power::new, "^", "pow"),
		;
		
		final BiFunction<QueryValueSource, QueryValueSource, QueryValueSource> ctor;
		final List<String>                                                     matches;
		
		Modifier(BiFunction<QueryValueSource, QueryValueSource, QueryValueSource> ctor, String... matches){
			this.ctor=ctor;
			this.matches=List.of(matches);
		}
		@Override
		public List<String> matches(){
			return matches;
		}
		QueryValueSource gnu(QueryValueSource l, QueryValueSource r){
			return ctor.apply(l, r);
		}
	}
	
	private enum Connector implements Matched{
		AND(QueryCheck.And::new, "&&", "&", "and"),
		OR(QueryCheck.Or::new, "||", "|", "or"),
		;
		
		final BiFunction<QueryCheck, QueryCheck, QueryCheck> ctor;
		final List<String>                                   matches;
		
		Connector(BiFunction<QueryCheck, QueryCheck, QueryCheck> ctor, String... matches){
			this.ctor=ctor;
			this.matches=List.of(matches);
		}
		@Override
		public List<String> matches(){
			return matches;
		}
		QueryCheck gnu(QueryCheck l, QueryCheck r){
			return ctor.apply(l, r);
		}
	}
	
	private enum Comparison implements Matched{
		EQUALS(QueryCheck.Equals::new, "==", "=", "is", "equals"),
		NOT_EQUALS((src, arg)->new QueryCheck.Equals(src, arg).negate(), "!=", "is not"),
		GREATER(QueryCheck.GreaterThan::new, ">"),
		GREATER_OR_EQUAL((src, arg)->new QueryCheck.LessThan(src, arg).negate(), ">="),
		LESSER(QueryCheck.LessThan::new, "<"),
		LESSER_OR_EQUAL((src, arg)->new QueryCheck.GreaterThan(src, arg).negate(), "<="),
		IN(QueryCheck.In::new, "in"),
		;
		
		final BiFunction<QueryValueSource, QueryValueSource, QueryCheck> ctor;
		final List<String>                                               matches;
		Comparison(BiFunction<QueryValueSource, QueryValueSource, QueryCheck> ctor, String... matches){
			this.ctor=ctor;
			this.matches=List.of(matches);
		}
		@Override
		public List<String> matches(){
			return matches;
		}
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
	
	public record FilterResult<T>(Consumer<T> argCheck, QueryCheck check){}
	
	
	private static final Map<FilterQuery<?>, FilterResult<?>> FILTER_CACHE=new ConcurrentHashMap<>();
	
	@SuppressWarnings("unchecked")
	public static <T> FilterResult<T> filter(Class<T> type, String expression){
		return (FilterResult<T>)FILTER_CACHE.computeIfAbsent(new FilterQuery<>(type, expression), QueryExpressionParser::parse);
	}
	
	private static <T> FilterResult<T> parse(FilterQuery<T> filterQuery){
		QueryCheck compiledCheck=expressionToCheck(filterQuery.type, filterQuery.expression, null);
		
		Log.trace("Compiled check for {}#cyan - \"{}#red\": {}#blue",
		          filterQuery.type.getSimpleName(), filterQuery.expression, compiledCheck);
		
		return new FilterResult<>(t->{}, QueryCheck.cached(compiledCheck));
	}
	
	
	private static Optional<Class<?>> argType(QueryValueSource arg){
		return Optional.ofNullable(arg.type());
	}
	
	private static class Tokenizer{
		private       int    pos;
		private final String str;
		private Tokenizer(String str){
			this.str=str;
		}
		
		private interface Test{
			boolean test(int i, char c);
		}
		
		private String advance(Test predicate){
			var start=pos;
			for(int i=pos;;i++){
				if(i>=str.length()){
					pos=i;
					break;
				}
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
			var inside=advance((i, c)->c!='}');
			pos++;
			return inside==null?"":inside;
		}
		
		public String brace(){
			skipWhite();
			if(str.charAt(pos)!='(') return null;
			pos++;
			
			int[] depth={1};
			return advance((i, c)->{
				if(c=='(') depth[0]++;
				if(c==')') depth[0]--;
				return depth[0]>0;
			});
		}
		
		public String string(){
			skipWhite();
			if(str.charAt(pos)!='\'') return null;
			pos++;
			var inside=advance((i, c)->c!='\'');
			pos++;
			return inside==null?"":inside;
		}
		public Number number(){
			var hex=regex(Pattern.compile("^[-+]?0[xX][0-9a-fA-F_]+"));
			if(hex!=null){
				hex=cleanNumber(hex, "0x");
				return parseLong(hex, 16);
			}
			var bin=regex(Pattern.compile("^[-+]??0[bB][0-1_]+"));
			if(bin!=null){
				bin=cleanNumber(bin, "0b");
				return parseLong(bin.replace("_", ""), 2);
			}
			var dec=regex(Pattern.compile("^[-+]??[0-9_]*\\.?[0-9_]*"));
			if(dec!=null){
				dec=cleanNumber(dec, null);
				if(dec.contains(".")){
					return parseDouble(dec.replace("_", ""));
				}
				return parseLong(dec);
			}
			
			return null;
		}
		
		private static String cleanNumber(String num, String prefix){
			if(num.charAt(0)=='+') num=num.substring(1);
			if(prefix!=null){
				if(num.charAt(0)=='-'){
					num="-"+num.substring(prefix.length()+1);
				}else{
					num=num.substring(prefix.length());
				}
			}
			num=num.replace("_", "");
			return num;
		}
		
		private String regex(Pattern pattern){
			skipWhite();
			var matcher=pattern.matcher(str.subSequence(pos, str.length()));
			if(!matcher.find()) return null;
			var result=matcher.group();
			if(result.isEmpty()) return null;
			pos+=result.length();
			return result;
		}
		
		private <T extends Enum<T>&Matched> T match(Class<T> toCheck){
			return match(toCheck, true);
		}
		private <T extends Enum<T>&Matched> T match(Class<T> toCheck, boolean require){
			var universe=EnumUniverse.of(toCheck);
			var result=universe.stream().flatMap(c->c.matches().stream().map(m->Map.entry(c, m)))
			                   .sorted(Comparator.comparingInt(e->-e.getValue().length()))
			                   .filter(e->match(e.getValue())).findFirst().map(Map.Entry::getKey);
			if(require&&result.isEmpty()){
				throw new InvalidQueryString(
					"Expected any "+toCheck.getSimpleName()+
					" ("+universe.stream().flatMap(c->c.matches().stream()).collect(Collectors.joining(", "))+")"+
					atTemplate(pos));
			}
			return result.orElse(null);
		}
		
		private boolean not(){
			return match("!", "not");
		}
		
		private boolean match(String... matches){
			for(var match : matches){
				if(match(match)){
					return true;
				}
			}
			return false;
		}
		private boolean match(String match){
			skipWhite();
			if(startsWith(match)){
				boolean validEnd;
				if(Character.isLetter(match.charAt(match.length()-1))){
					var c=str.charAt(pos+match.length());
					validEnd=Character.isWhitespace(c)||List.of('(', ')', '[', ']', '{', '}', '<', '>', '!', '?', '&', '|', '=').contains(c);
				}else validEnd=true;
				if(validEnd){
					pos+=match.length();
					return true;
				}
			}
			return false;
		}
		
		private boolean startsWith(String match){
			return str.regionMatches(true, pos, match, 0, match.length());
		}
		
		private void skipWhite(){
			for(int i=pos;i<str.length();i++){
				if(Character.isWhitespace(str.charAt(i))) pos++;
				else break;
			}
		}
		@Override
		public String toString(){
			return str.substring(pos);
		}
		
		InvalidQueryString nextWordBad(String message){
			var    pos =this.pos;
			var    word=regex(Pattern.compile("^\\S+"));
			String msg;
			if(word==null) msg="Unexpected end. "+message;
			else msg="\""+word+"\" "+message;
			
			throw new InvalidQueryString(msg+atTemplate(pos));
		}
		
		private String atTemplate(int pos){
			return "\n"+
			       str+"\n"+
			       " ".repeat(pos)+"^";
		}
	}
	
	private static class Parser{
		
		private final Class<?>  type;
		private final Tokenizer tokenizer;
		private       boolean   notNext=false;
		private final int[]     argCounter;
		
		private Parser(Class<?> type, Tokenizer tokenizer, int[] argCounter){
			this.type=type;
			this.tokenizer=tokenizer;
			this.argCounter=argCounter;
		}
		
		QueryCheck parse(){
			
			QueryCheck check=null;
			while(true){
				tokenizer.skipWhite();
				if(tokenizer.str.length()==tokenizer.pos){
					if(check==null) throw new InvalidQueryString("Unexpected end");
					return check;
				}
				
				if(!notNext&&tokenizer.not()){
					notNext=true;
					continue;
				}
				
				if(check!=null){
					var con=tokenizer.match(Connector.class);
					var r  =check();
					check=con.gnu(check, r);
				}else{
					check=check();
				}
			}
		}
		
		private QueryCheck check(){
			var check=nextCheck();
			if(check==null) return null;
			if(notNext){
				notNext=false;
				check=check.negate();
			}
			return check;
		}
		private QueryCheck nextCheck(){
			var brace=tokenizer.brace();
			if(brace!=null){
				return expressionToCheck(type, brace, argCounter);
			}
			
			var field=readField();
			if(field!=null){
				var comp  =tokenizer.match(Comparison.class);
				var source=source();
				checkComparison(field, source);
				return comp.ctor.apply(field, source);
			}
			
			var str=tokenizer.string();
			if(str!=null){
				if(Comparison.IN.matches().stream().noneMatch(tokenizer::match)){
					throw tokenizer.nextWordBad(
						"Expected "+String.join(" or ", Comparison.IN.matches())+
						" after left hand string literal"
					);
				}
				var pos   =tokenizer.pos;
				var source=source();
				if(source.deep().noneMatch(s->s instanceof QueryValueSource.Field)){
					tokenizer.pos=pos;
					throw tokenizer.nextWordBad("No field on left or right side of IN");
				}
				
				return new QueryCheck.In(new QueryValueSource.Literal(str), source);
			}
			
			throw tokenizer.nextWordBad("Unexpected token");
		}
		
		private QueryValueSource readField(){
			var name=tokenizer.field();
			if(name==null) return null;
			QueryValueSource field=findField(name);
			return modifiedData(field);
		}
		
		private void checkComparison(QueryValueSource l, QueryValueSource r){
			argType(l).ifPresent(lType->{
				argType(r).ifPresent(rType->{
					if(lType!=rType&&UtilL.instanceOf(lType, rType)){
						throw new ClassCastException("Cannot cast "+lType+" to "+rType);
					}
				});
			});
		}
		
		private QueryValueSource.Field findField(String fieldName){
			if(IOInstance.isInstance(type)){
				IOField<?, ?> field=Struct.ofUnknown(type).getFields().byName(fieldName)
				                          .orElseThrow(()->noField(fieldName));
				if(Utils.isVirtual(field, null)){
					throw new NotImplementedException("Virtual field access not implemented");
				}
				return new QueryValueSource.Field.IO(field);
			}
			
			try{
				var getter=type.getMethod("get"+TextUtil.firstToUpperCase(fieldName));
				if(getter.getReturnType()!=void.class){
					return new QueryValueSource.Field.Getter(getter);
				}
			}catch(NoSuchMethodException ignored){}
			
			var fieldO=Arrays.stream(type.getFields()).filter(f->f.getName().equals(fieldName)).findAny();
			if(fieldO.isPresent()){
				return new QueryValueSource.Field.Raw(fieldO.get());
			}
			
			throw noField(fieldName);
		}
		
		private RuntimeException noField(String fieldName){
			return new InvalidQueryString(fieldName+" does not exist in "+type.getName());
		}
		
		private QueryValueSource modifiedData(QueryValueSource data){
			while(true){
				var mod=tokenizer.match(Modifier.class, false);
				if(mod==null) break;
				var src=nextSource();
				data=mod.gnu(data, src);
			}
			return data;
		}
		
		private QueryValueSource source(){
			var soruce=nextSource();
			return modifiedData(soruce);
		}
		private QueryValueSource nextSource(){
			var argStr=tokenizer.squiggly();
			if(argStr!=null){
				int index;
				if(argStr.isEmpty()){
					index=argCounter[0]++;
				}else{
					index=Integer.parseInt(argStr);
				}
				return new QueryValueSource.GetArray(index, new QueryValueSource.Root());
			}
			var num=tokenizer.number();
			if(num!=null){
				return new QueryValueSource.Literal(num);
			}
			var str=tokenizer.string();
			if(str!=null){
				return new QueryValueSource.Literal(str);
			}
			var fieldName=tokenizer.field();
			if(fieldName!=null){
				return findField(fieldName);
			}
			
			throw tokenizer.nextWordBad("is not a valid literal or value source");
		}
	}
	
	private static QueryCheck expressionToCheck(Class<?> type, String expression, int[] argCounter){
		if(argCounter==null) argCounter=new int[1];
		return new Parser(type, new Tokenizer(expression), argCounter).parse();
	}
}
