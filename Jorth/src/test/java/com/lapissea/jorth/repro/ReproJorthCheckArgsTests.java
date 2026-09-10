package com.lapissea.jorth.repro;

import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.FunctionInfo;
import com.lapissea.jorth.lang.type.ClassInfo;
import com.lapissea.jorth.lang.type.GenericType;
import com.lapissea.jorth.lang.type.TypeSource;
import org.testng.annotations.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class ReproJorthCheckArgsTests{
	
	public static class Candidate{
		public Candidate(Object first, int second){ }
	}
	
	public static class Overloaded{
		public Overloaded(Object first, int second)   { }
		public Overloaded(Object first, String second){ }
	}
	
	private static ClassInfo info(Class<?> type){
		var source = TypeSource.of(null, ReproJorthCheckArgsTests.class.getClassLoader());
		return new ClassInfo.OfClass(source, type);
	}
	
	@Test(expectedExceptions = MalformedJorth.class)
	void matchingObjectDoesNotHideLaterMismatch() throws MalformedJorth{
		info(Candidate.class).getFunction(new FunctionInfo.Signature("<init>", List.of(GenericType.OBJECT, GenericType.STRING)));
	}
	
	@Test(expectedExceptions = MalformedJorth.class)
	void matchingObjectDoesNotHideWrongArity() throws MalformedJorth{
		info(Candidate.class).getFunction(new FunctionInfo.Signature("<init>", List.of(GenericType.OBJECT)));
	}
	
	@Test
	void matchingConstructorResolves() throws MalformedJorth{
		var signature = new FunctionInfo.Signature("<init>", List.of(GenericType.OBJECT, GenericType.INT));
		assertThat(info(Candidate.class).getFunction(signature).makeSignature()).isEqualTo(signature);
	}
	
	@Test
	void overloadResolutionChecksArgumentsAfterObject() throws MalformedJorth{
		var info = info(Overloaded.class);
		for(var second : List.of(GenericType.INT, GenericType.STRING)){
			var signature = new FunctionInfo.Signature("<init>", List.of(GenericType.OBJECT, second));
			assertThat(info.getFunction(signature).makeSignature()).isEqualTo(signature);
		}
	}
}
