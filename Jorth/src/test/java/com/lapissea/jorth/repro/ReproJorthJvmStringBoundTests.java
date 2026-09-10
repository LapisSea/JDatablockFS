package com.lapissea.jorth.repro;

import com.lapissea.jorth.lang.ClassName;
import com.lapissea.jorth.lang.type.GenericType;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

public class ReproJorthJvmStringBoundTests{
	
	@DataProvider
	Object[][] primitives(){
		return new Object[][]{
			{boolean.class}, {byte.class}, {short.class}, {char.class}, {int.class},
			{long.class}, {float.class}, {double.class}, {void.class}
		};
	}
	
	@Test(dataProvider = "primitives", expectedExceptions = IllegalArgumentException.class,
	      expectedExceptionsMessageRegExp = "Type variable bound must be a reference type: .*")
	void primitiveBoundIsRejected(Class<?> primitive){
		GenericType.of(primitive).withTypeArgName(ClassName.dotted("T"));
	}
	
	@Test(expectedExceptions = IllegalArgumentException.class,
	      expectedExceptionsMessageRegExp = "Type variable bound must be a reference type: .*")
	void directConstructionRejectsPrimitiveBound(){
		new GenericType(GenericType.INT.raw(), Optional.of(ClassName.dotted("T")), 0, List.of());
	}
	
	@Test
	void referenceBoundPreservesVariableSignatureAndErasedDescriptor(){
		var type = GenericType.of(Number.class).withTypeArgName(ClassName.dotted("T"));
		assertEncoding(type, true, "TT;");
		assertEncoding(type, false, "Ljava/lang/Number;");
		assertEncoding(type.arrayType(), true, "[TT;");
		assertEncoding(type.arrayType(), false, "[Ljava/lang/Number;");
	}
	
	@Test
	void plainPrimitivePreservesDescriptor(){
		assertEncoding(GenericType.BYTE, true, "B");
		assertEncoding(GenericType.BYTE, false, "B");
		assertEncoding(GenericType.BYTE.arrayType(), true, "[B");
	}
	
	private static void assertEncoding(GenericType type, boolean generics, String expected){
		var text = new StringBuilder();
		type.jvmString(text, generics);
		assertThat(text.toString()).isEqualTo(expected);
		assertThat(type.jvmStringLen(generics)).isEqualTo(expected.length());
		assertThat(type.jvmString(generics).toString()).isEqualTo(expected);
	}
}
