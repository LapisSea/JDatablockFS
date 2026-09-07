package com.lapissea.jorth.repro;

import com.lapissea.jorth.lang.FunctionInfo;
import com.lapissea.jorth.lang.type.TypeSource;
import com.lapissea.jorth.lang.type.Visibility;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Constructor metadata must preserve all four JVM member visibility levels. */
public class ReproJorthCtorVisibilityTests{
	public static class PublicCtor{
		public PublicCtor(){ }
	}
	static class ProtectedCtor{
		protected ProtectedCtor(){ }
	}
	static class PackageCtor{
		PackageCtor(){ }
	}
	static class PrivateCtor{
		private PrivateCtor(){ }
	}

	private static Visibility visibilityOf(Class<?> type) throws NoSuchMethodException{
		var source = TypeSource.of(null, type.getClassLoader());
		return new FunctionInfo.OfConstructor(source, type.getDeclaredConstructor()).visibility();
	}

	@Test
	void publicConstructorVisibilityShouldBePublic() throws NoSuchMethodException{
		assertThat(visibilityOf(PublicCtor.class)).isEqualTo(Visibility.PUBLIC);
	}

	@Test
	void nonPublicConstructorVisibilityCorrect() throws NoSuchMethodException{
		assertThat(visibilityOf(ProtectedCtor.class)).isEqualTo(Visibility.PROTECTED);
		assertThat(visibilityOf(PackageCtor.class)).isEqualTo(Visibility.PACKAGE_PRIVATE);
		assertThat(visibilityOf(PrivateCtor.class)).isEqualTo(Visibility.PRIVATE);
	}
}
