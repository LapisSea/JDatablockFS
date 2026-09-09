package com.lapissea.jorth.repro;

import com.lapissea.jorth.ClassDefinition;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import org.testng.annotations.Test;

public class ReproJorthErrorTypeTests{

	public static class NoDefaultConstructor{
		public NoDefaultConstructor(int value){ }
	}

	@Test(expectedExceptions = MalformedJorth.class, expectedExceptionsMessageRegExp = "Failed to return on .*")
	void invalidImplicitReturnThrowsMalformedJorth() throws MalformedJorth{
		var cd = new ClassDefinition(null).name(ClassName.dotted("reproerrortype.MissingReturn"));
		cd.function("missingReturn").returns(int.class).body();
		cd.getClassFile();
	}

	@Test(expectedExceptions = MalformedJorth.class, expectedExceptionsMessageRegExp = ".*noSuchMethod.*")
	void missingMethodThrowsMalformedJorth() throws MalformedJorth{
		var cd = new ClassDefinition(null).name(ClassName.dotted("reproerrortype.BadCall"));
		cd.function("badCall").staticAcc().returns(String.class)
		  .body().val("hello").call("noSuchMethod");
	}

	@Test(expectedExceptions = MalformedJorth.class)
	void invalidGeneratedConstructorThrowsMalformedJorth() throws MalformedJorth{
		var cd = new ClassDefinition(null).name(ClassName.dotted("reproerrortype.BadConstructor"));
		cd.extendsType(NoDefaultConstructor.class);
		cd.getClassFile();
	}

	@Test(expectedExceptions = MalformedJorth.class, expectedExceptionsMessageRegExp = "Deferred failure")
	void lazyBlockFailureThrowsMalformedJorth() throws MalformedJorth{
		var cd = new ClassDefinition(null).name(ClassName.dotted("reproerrortype.BadLazyBlock"));
		cd.function("run").staticAcc().body().lazyBlock(code -> {
			throw new MalformedJorth("Deferred failure");
		});
		cd.getClassFile();
	}
}
