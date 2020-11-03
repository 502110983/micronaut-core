package io.micronaut.scala

import java.lang.annotation.Annotation
import java.util
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

import io.micronaut.aop.writer.AopProxyWriter
import io.micronaut.context.annotation._
import io.micronaut.core.annotation.{AnnotationMetadata, AnnotationValue, Internal}
import io.micronaut.core.naming.NameUtils
import io.micronaut.inject.annotation.{AnnotationMetadataHierarchy, AnnotationMetadataReference, DefaultAnnotationMetadata}
import io.micronaut.inject.configuration.PropertyMetadata
import io.micronaut.inject.processing.ProcessedTypes
import io.micronaut.inject.visitor.VisitorContext
import io.micronaut.inject.writer.{BeanDefinitionReferenceWriter, BeanDefinitionVisitor, BeanDefinitionWriter, ExecutableMethodWriter}
import io.micronaut.scala.AnnBeanElementVisitor._
import io.micronaut.scala.TypeFunctions._
import javax.inject.{Inject, Qualifier, Scope}

import scala.collection.convert.ImplicitConversions.`collection AsScalaIterable`
import scala.collection.mutable
import scala.tools.nsc.Global

object AnnBeanElementVisitor {
  val AROUND_TYPE = "io.micronaut.aop.Around"
  val ANN_CONSTRAINT = "javax.validation.Constraint"
  val ANN_VALID = "javax.validation.Valid"
  val ANN_VALIDATED = "io.micronaut.validation.Validated"
  val ANN_CONFIGURATION_ADVICE = "io.micronaut.runtime.context.env.ConfigurationAdvice"

  val FILTERED_ANCESTORS = List("java.lang.Object")
}

class AnnBeanElementVisitor(global:Global, concreteClass:Global#ClassDef, visitorContext:VisitorContext) {
  private val concreteClassMetadata = Globals.metadataBuilder.getOrCreate(new SymbolFacade(concreteClass.symbol))

  private val isFactoryType = concreteClassMetadata.hasStereotype(classOf[Factory])

  private val isConfigurationPropertiesType = concreteClassMetadata.hasDeclaredStereotype(classOf[ConfigurationReader]) || concreteClassMetadata.hasDeclaredStereotype(classOf[EachProperty])

  private val factoryMethodIndex = new AtomicInteger(0)

  private val originatingElement = new ScalaClassElement(concreteClass.symbol.asInstanceOf[Global#ClassSymbol], concreteClassMetadata, visitorContext)

  private val isAopProxyType = concreteClassMetadata.hasStereotype("io.micronaut.aop.Around") || concreteClassMetadata.hasStereotype(classOf[Executable])

  private val isExecutableType = isAopProxyType || concreteClassMetadata.hasStereotype(classOf[Executable]);

  private val constructorParameterInfo = ExecutableElementParamInfo.populateParameterData(
    Option.empty,
    concreteConstructorFor(concreteClass),
    Collections.emptyMap)

  private val hasQualifier = concreteClassMetadata.hasStereotype(classOf[Qualifier]) && !concreteClass.symbol.isAbstract

  private val isDeclaredBean = isExecutableType || concreteClassMetadata.hasStereotype(classOf[Scope]) || concreteClassMetadata.hasStereotype(classOf[DefaultScope]) || constructorParameterInfo.metadata.hasStereotype(classOf[Inject]) || hasQualifier

  private val metadataBuilder = new ScalaConfigurationMetadataBuilder()

  val beanDefinitionWriters = new mutable.HashMap[ElementFacade, BeanDefinitionVisitor]

  private def concreteConstructorFor(classDef: Global#ClassDef): Option[Global#Symbol] = {
    val constructors = classDef.impl.body.filter(_.symbol.isConstructor)

    constructors.headOption.map(_.symbol)
  }

  private def isExecutableThroughType(
    enclosingElement: Global#ClassSymbol,
    annotationMetadataHierarchy: AnnotationMetadata,
    declaredMetadata: AnnotationMetadata,
    isPublic: Boolean) = (isExecutableType && (isPublic || concreteClass.symbol == enclosingElement)) ||
              annotationMetadataHierarchy.hasDeclaredStereotype(classOf[Executable]) ||
              declaredMetadata.hasStereotype(classOf[Executable])

  private def isInheritedAndNotPublic(concreteClass: Global#Symbol, declaringClass: Global#Symbol, methodOrField: Global#Symbol) = {
    val packageOfDeclaringClass = declaringClass.owner
    val packageOfConcreteClass = concreteClass.owner
    (declaringClass ne concreteClass) &&
      !(packageOfDeclaringClass.fullName == packageOfConcreteClass.fullName) &&
      (methodOrField.isProtected || !methodOrField.isPublic)
  }

  private def visitAnnotatedMethod(methodSymbol: Global#Symbol,
                                   annotationMetadata: AnnotationMetadata,
                                   beanDefinitionWriter: BeanDefinitionVisitor,
                                   overrides:Option[Global#Symbol]): Unit = {

    //    val params = populateParameterData(null, method, Collections.emptyMap)
    //    val returnType = method.getReturnType
    val declaringClass = methodSymbol.enclClass
    //
    //    if (declaringClass == null) return
    //
    val isParent = !(declaringClass.fullName == this.concreteClass.symbol.fullName)
    val overridingMethod = overrides.getOrElse(methodSymbol)
    val overridingClass = overridingMethod.enclClass
    val overridden = isParent && overridingClass != null && !(overridingClass.fullName == declaringClass.fullName)
    //
    val isPackagePrivate = methodSymbol.hasPackageFlag || methodSymbol.privateWithin == declaringClass.owner
    val isPrivate = methodSymbol.isPrivate
     if (overridden && !(isPrivate || isPackagePrivate)) { // bail out if the method has been overridden, since it will have already been handled
      return
    }

    val packageOfOverridingClass = overridingClass.owner
    val packageOfDeclaringClass = declaringClass.owner
    val isPackagePrivateAndPackagesDiffer = overridden && isPackagePrivate &&
      !(packageOfOverridingClass.fullName == packageOfDeclaringClass.fullName)
    var requiresReflection = isPrivate || isPackagePrivateAndPackagesDiffer
    val overriddenInjected = overridden && Globals.metadataBuilder.getOrCreate(SymbolFacade(overridingMethod)).hasDeclaredStereotype(classOf[Inject])

    if (isParent && isPackagePrivate && !isPackagePrivateAndPackagesDiffer && overriddenInjected) { // bail out if the method has been overridden by another method annotated with @Inject
      return
    }
    if (isParent && overridden && !overriddenInjected && !isPackagePrivateAndPackagesDiffer && !isPrivate) { // bail out if the overridden method is package private and in the same package
      // and is not annotated with @Inject
      return
    }
    if (!requiresReflection && isInheritedAndNotPublic(this.concreteClass.symbol, declaringClass, methodSymbol)) requiresReflection = true

    val paramGenerics = typeGenericsForParamsAndArgs(
      methodSymbol.owner.originalInfo.typeSymbol.originalInfo.typeParams,
      methodSymbol.originalInfo.typeArgs
    )

    val params = ExecutableElementParamInfo.populateParameterData(Option.empty, Some(methodSymbol), paramGenerics)

    var argType = resolveType(methodSymbol.originalInfo.resultType)

    val methodClass = (if (methodSymbol.enclClass.isTrait) concreteClass.symbol else methodSymbol.enclClass).fullName

    if (methodSymbol.enclClass.isTrait && overridden) return // TODO make this work by dealing with linearization : https://riptutorial.com/scala/example/14607/linearization

    if (annotationMetadata.hasDeclaredStereotype(ProcessedTypes.POST_CONSTRUCT)) {
      //      final AopProxyWriter aopWriter = resolveAopWriter(writer);
      //      if (aopWriter != null && !aopWriter.isProxyTarget()) writer = aopWriter
      beanDefinitionWriter.visitPostConstructMethod(
        methodClass,
        requiresReflection,
        argType,
        methodSymbol.nameString,
        params.parameters,
        params.parameterMetadata,
        params.genericTypes,
        annotationMetadata
      )
    } else if (annotationMetadata.hasDeclaredStereotype(ProcessedTypes.PRE_DESTROY)) {
//        val aopWriter = resolveAopWriter(writer)
//        if (aopWriter != null && !aopWriter.isProxyTarget) writer = aopWriter
      beanDefinitionWriter.visitPreDestroyMethod(
            methodClass,
            requiresReflection,
            argType,
            methodSymbol.nameString,
            params.parameters,
            params.parameterMetadata,
            params.genericTypes,
            annotationMetadata)
    } else if (annotationMetadata.hasDeclaredStereotype(classOf[Inject]) || annotationMetadata.hasDeclaredStereotype(classOf[ConfigurationInject])) {
        //val writer = getOrCreateBeanDefinitionWriter(concreteClass, concreteClass.getQualifiedName)
       beanDefinitionWriter.visitMethodInjectionPoint(
         methodClass,
        requiresReflection,
        argType,
        methodSymbol.nameString,
        params.parameters,
        params.parameterMetadata,
        params.genericTypes,
        annotationMetadata)
      } //      else error("Unexpected call to visitAnnotatedMethod(%s)", method)
  }

  private def addAnnotation(annotationMetadata: AnnotationMetadata, annotation: String) =
    Globals.metadataBuilder.annotate(annotationMetadata, io.micronaut.core.annotation.AnnotationValue.builder(annotation).build)

  private def visitType(classElement: Global#ClassDef, classAnnotationMetadata: AnnotationMetadata):Unit = {
    val classElementQualifiedName = classElement.symbol.fullName
//    if (visitedTypes.contains(classElementQualifiedName)) { // bail out if already visited
//      return o
//    }
    val isInterface = classElement.symbol.isInterface
//    visitedTypes.add(classElementQualifiedName)
    var typeAnnotationMetadata = classAnnotationMetadata
    if (isConfigurationPropertiesType) { // TODO: populate documentation
      val configurationMetadata = metadataBuilder.visitProperties(concreteClass.symbol, null)
      if (isInterface) {
        typeAnnotationMetadata = addAnnotation(typeAnnotationMetadata, ANN_CONFIGURATION_ADVICE)
      }
    }
//    if (typeAnnotationMetadata.hasStereotype(INTRODUCTION_TYPE)) {
//      val aopProxyWriter = createIntroductionAdviceWriter(classElement)
//      val constructor = if (JavaModelUtils.resolveKind(classElement, ElementKind.CLASS).isPresent) modelUtils.concreteConstructorFor(classElement, annotationUtils)
//      else null
//      val constructorData = if (constructor != null) populateParameterData(null, constructor, Collections.emptyMap)
//      else null
//      if (constructorData != null) aopProxyWriter.visitBeanDefinitionConstructor(constructorData.getAnnotationMetadata, constructorData.isRequiresReflection, constructorData.getParameters, constructorData.getParameterMetadata, constructorData.getGenericTypes)
//      else aopProxyWriter.visitBeanDefinitionConstructor(AnnotationMetadata.EMPTY_METADATA, false)
//      beanDefinitionWriters.put(classElementQualifiedName, aopProxyWriter)
//      visitIntroductionAdviceInterface(classElement, typeAnnotationMetadata, aopProxyWriter)
//      if (!isInterface) {
//        val elements = classElement.getEnclosedElements.stream.filter // already handled the public ctor
//        ((element: Element) => element.getKind ne CONSTRUCTOR).collect(Collectors.toList)
//        scan(elements, o)
//      }
//      else null
//    }
//    else {
      val enclosingElement = classElement.symbol.owner
      // don't process inner class unless this is the visitor for it
      val qualifiedName = concreteClass.symbol.fullName
      if (!enclosingElement.isClass || qualifiedName == classElementQualifiedName) {
        if (qualifiedName == classElementQualifiedName) if (isDeclaredBean) { // we know this class has supported annotations so we need a beandef writer for it
          val packageElement = classElement.symbol.owner
//          if (packageElement.isUnnamed) {
//            error(classElement, "Micronaut beans cannot be in the default package")
//            return null
//          }
//          val beanDefinitionWriter = getOrCreateBeanDefinitionWriter(classElement, qualifiedName)
//          if (isAopProxyType) {
//            if (classElement.symbol.isFinal) {
//              //error(classElement, "Cannot apply AOP advice to final class. Class must be made non-final to support proxying: " + classElement)
//              return null
//            }
//            val interceptorTypes = classAnnotationMetadata.getAnnotationNamesByStereotype(AROUND_TYPE).toArray
//            resolveAopProxyWriter(beanDefinitionWriter, aopSettings, false, this.constructorParameterInfo, interceptorTypes)
//          }
//        }
//        else if (modelUtils.isAbstract(classElement)) return null
//        val elements = classElement.getEnclosedElements.stream.filter((element: Element) => element.getKind ne CONSTRUCTOR).collect(Collectors.toList)
//        if (isConfigurationPropertiesType) { // handle non @Inject, @Value fields as config properties
//          val members = elementUtils.getAllMembers(classElement)
//          ElementFilter.fieldsIn(members).forEach((field: VariableElement) => {
//            def foo(field: VariableElement) = {
//              val fieldAnnotationMetadata = annotationUtils.getAnnotationMetadata(field)
//              val isConfigBuilder = fieldAnnotationMetadata.hasStereotype(classOf[ConfigurationBuilder])
//              if (modelUtils.isStatic(field)) return
//              // its common for builders to be initialized, so allow final
//              if (!modelUtils.isFinal(field) || isConfigBuilder) visitConfigurationProperty(field, fieldAnnotationMetadata)
//            }
//
//            foo(field)
//          })
//          ElementFilter.methodsIn(members).forEach((method: ExecutableElement) => {
//            def foo(method: ExecutableElement) = {
//              val isCandidateMethod = !modelUtils.isStatic(method) && !modelUtils.isPrivate(method) && !modelUtils.isAbstract(method)
//              if (isCandidateMethod) {
//                val e = method.getEnclosingElement
//                if (e.isInstanceOf[TypeElement] && !(e == classElement)) {
//                  val methodName = method.getSimpleName.toString
//                  if (method.getParameters.size == 1 && NameUtils.isSetterName(methodName)) visitConfigurationPropertySetter(method)
//                  else if (NameUtils.isGetterName(methodName)) {
//                    val writer = getOrCreateBeanDefinitionWriter(concreteClass, concreteClass.getQualifiedName)
//                    if (!writer.isValidated) writer.setValidated(IS_CONSTRAINT.test(annotationUtils.getAnnotationMetadata(method)))
//                  }
//                }
//              }
//            }
//
//            foo(method)
//          })
//        }
//        else {
//          val superClass = modelUtils.superClassFor(classElement)
//          if (superClass != null && !modelUtils.isObjectClass(superClass)) superClass.accept(this, o)
//        }
//        scan(elements, o)
      }
    }
  }

  private def visitExecutableMethod(methodSymbol: Global#Symbol, methodAnnotationMetadata: AnnotationMetadata, beanDefinitionWriter: BeanDefinitionVisitor):Unit = {
    val declaringClass = methodSymbol.owner
    if (declaringClass == null || declaringClass.isRoot) {
      return
    }

    val isOwningClass: Boolean = declaringClass.fullName == concreteClass.symbol.fullName

    if (isOwningClass && concreteClass.symbol.isAbstract) { // && !(concreteClassMetadata.hasStereotype(INTRODUCTION_TYPE))) {
      return
    }

//    if (!(isOwningClass) && modelUtils.overridingOrHidingMethod(method, concreteClass, true).isPresent) {
//      return
//    }

    val returnTypeGenerics: java.util.Map[String, AnyRef] = new java.util.LinkedHashMap[String, AnyRef]
    //genericUtils.resolveBoundGenerics(method.getEnclosingElement.asInstanceOf[TypeElement], returnType, genericUtils.buildGenericTypeArgumentElementInfo(concreteClass)).forEach((key: String, value: TypeMirror) => returnTypeGenerics.put(key, modelUtils.resolveTypeReference(value)))

    val resolvedReturnType =  resolveType(methodSymbol.originalInfo.resultType)

    val enclosingElement = methodSymbol.enclClass

//    var boundTypes: Map[String, AnyRef] = genericUtils.buildGenericTypeArgumentInfo(concreteClass).get(enclosingElement.getQualifiedName.toString)
//    if (boundTypes == null) {
//     var boundTypes: Map[String, AnyRef]  = Collections.emptyMap()
//    }

    val params = ExecutableElementParamInfo.populateParameterData(methodSymbol) //, boundTypes)

    // This method requires pre-processing. See Executable#processOnStartup()
    val preprocess: Boolean = methodAnnotationMetadata.isTrue(classOf[Executable], "processOnStartup")
    if (preprocess) {
      beanDefinitionWriter.setRequiresMethodProcessing(true)
    }

    var typeRef: Any = enclosingElement.fullName //modelUtils.resolveTypeReference(method.getEnclosingElement)
//    if (typeRef == null) {
//      typeRef = modelUtils.resolveTypeReference(concreteClass)
//    }

    //val proxyWriter: AopProxyWriter = resolveAopWriter(beanWriter)
    var executableMethodWriter:ExecutableMethodWriter = null
 //   if (proxyWriter == null || proxyWriter.isProxyTarget) {
      executableMethodWriter = beanDefinitionWriter.visitExecutableMethod(
        typeRef,
        resolvedReturnType,
        resolvedReturnType,
        returnTypeGenerics,
        methodSymbol.nameString,
        params.parameters,
        params.genericParameters,
        params.parameterMetadata,
        params.genericTypes,
        methodAnnotationMetadata,
        enclosingElement.isInterface,
        false) //method.isDefault)
//    }


//    if (methodAnnotationMetadata.hasStereotype(classOf[Adapter])) {
//      visitAdaptedMethod(method, methodAnnotationMetadata)
//    }


    // shouldn't visit around advice on an introduction advice instance
    if (!beanDefinitionWriter.isInstanceOf[AopProxyWriter]) {
      val isConcrete: Boolean = concreteClass.symbol.isConcreteClass
      val isPublic: Boolean = methodSymbol.isPublic || methodSymbol.hasPackageFlag
//      if ((isAopProxyType && isPublic) || (!(isAopProxyType) && methodAnnotationMetadata.hasStereotype(AROUND_TYPE)) || (methodAnnotationMetadata.hasDeclaredStereotype(AROUND_TYPE) && isConcrete)) {
//        val interceptorTypes: Array[AnyRef] = methodAnnotationMetadata.getAnnotationNamesByStereotype(AROUND_TYPE).toArray
//        val settings: OptionalValues[Boolean] = methodAnnotationMetadata.getValues(AROUND_TYPE, classOf[Boolean])
//        val aopProxyWriter: AopProxyWriter = resolveAopProxyWriter(beanWriter, settings, false, this.constructorParameterInfo, interceptorTypes)
//        aopProxyWriter.visitInterceptorTypes(interceptorTypes)
//        val isAnnotationReference: Boolean = methodAnnotationMetadata.isInstanceOf[AnnotationMetadataReference]
//        var aroundMethodMetadata: AnnotationMetadata = null
//        if (!(isAnnotationReference) && executableMethodWriter != null) {
//          aroundMethodMetadata = new AnnotationMetadataReference(executableMethodWriter.getClassName, methodAnnotationMetadata)
//        }
//        else {
//          if (methodAnnotationMetadata.isInstanceOf[AnnotationMetadataHierarchy]) {
//            aroundMethodMetadata = methodAnnotationMetadata
//          }
//          else {
//            aroundMethodMetadata = new AnnotationMetadataHierarchy(concreteClassMetadata, methodAnnotationMetadata)
//          }
//        }
//        if (method.symbol.isFinal) {
//          if (methodAnnotationMetadata.hasDeclaredStereotype(AROUND_TYPE)) {
//            error(method, "Method defines AOP advice but is declared final. Change the method to be non-final in order for AOP advice to be applied.")
//          }
//          else {
//            if (isAopProxyType && isPublic && !(declaringClass == concreteClass)) {
//              if (executableMethodWriter == null) {
//                beanDefinitionWriter.visitExecutableMethod(
//                  typeRef,
//                  resolvedReturnType,
//                  resolvedReturnType,
//                  returnTypeGenerics,
//                  method.symbol.name.toString,
//                  params.parameters,
//                  params.genericParameters,
//                  params.annotationMetadata,
//                  params.genericTypes,
//                  methodAnnotationMetadata,
//                  enclosingElement.isInterface, false) //isDefault)
//              }
//            }
//            else {
//              error(method, "Public method inherits AOP advice but is declared final. Either make the method non-public or apply AOP advice only to public methods declared on the class.")
//            }
//          }
//        }
//        else {
//          if (aroundMethodMetadata.hasStereotype(classOf[Executable])) {
//            aopProxyWriter.visitExecutableMethod(typeRef, resolvedReturnType, resolvedReturnType, returnTypeGenerics, method.getSimpleName.toString, params.getParameters, params.getGenericParameters, params.getParameterMetadata, params.getGenericTypes, aroundMethodMetadata, JavaModelUtils.isInterface(enclosingElement), method.isDefault)
//          }
//          aopProxyWriter.visitAroundMethod(typeRef, resolvedReturnType, resolvedReturnType, returnTypeGenerics, method.getSimpleName.toString, params.getParameters, params.getGenericParameters, params.getParameterMetadata, params.getGenericTypes, aroundMethodMetadata, JavaModelUtils.isInterface(enclosingElement), method.isDefault)
//        }
      }
      else {
        if (executableMethodWriter == null) {
          beanDefinitionWriter.visitExecutableMethod(
            typeRef,
            resolvedReturnType,
            resolvedReturnType,
            returnTypeGenerics,
            methodSymbol.nameString,
            params.parameters,
            params.genericParameters,
            params.parameterMetadata,
            params.genericTypes,
            methodAnnotationMetadata,
            enclosingElement.isInterface,
            false) //method.isDefault)
        }
      }
   }

  private def visitExecutable(methodSymbol: Global#Symbol,
                              beanDefinitionWriter: BeanDefinitionVisitor,
                              overridden:Option[Global#Symbol]):Unit = {
    val annotationMetadata = Globals.metadataBuilder.getOrCreate(SymbolFacade(methodSymbol))

    var methodAnnotationMetadata = if (annotationMetadata.isInstanceOf[AnnotationMetadataHierarchy]) annotationMetadata
    else new AnnotationMetadataHierarchy(concreteClassMetadata, annotationMetadata)

    if (isFactoryType && methodAnnotationMetadata.hasDeclaredStereotype(classOf[Bean], classOf[Scope]) /* && (returnKind eq TypeKind.DECLARED) */) {
      visitBeanFactoryMethod(methodSymbol, methodAnnotationMetadata)
    } else {
      val injected = annotationMetadata.hasDeclaredStereotype(classOf[Inject])
      val postConstruct = annotationMetadata.hasDeclaredStereotype(ProcessedTypes.POST_CONSTRUCT)
      val preDestroy = annotationMetadata.hasDeclaredStereotype(ProcessedTypes.PRE_DESTROY)
      if (injected || postConstruct || preDestroy || annotationMetadata.hasDeclaredStereotype(classOf[ConfigurationInject])) {
        if (isDeclaredBean) {
          visitAnnotatedMethod(methodSymbol, annotationMetadata, beanDefinitionWriter, overridden)
        } else if (injected) { // DEPRECATE: This behaviour should be deprecated in 2.0
          visitAnnotatedMethod(methodSymbol, annotationMetadata, beanDefinitionWriter, overridden)
        }
      }

      val hasInvalidModifiers = methodSymbol.isAbstract || methodSymbol.isStatic || methodSymbol.isPrivate || methodAnnotationMetadata.hasAnnotation(classOf[Internal])
      val isPublic = methodSymbol.isPublic && !hasInvalidModifiers
      val isExecutable = !hasInvalidModifiers && (isExecutableThroughType(methodSymbol.owner.asInstanceOf[Global#ClassSymbol], methodAnnotationMetadata, annotationMetadata, isPublic) || annotationMetadata.hasStereotype(AROUND_TYPE))

      var hasConstraints = false
      if (isDeclaredBean
        && !methodAnnotationMetadata.hasStereotype(ANN_VALID)
        && methodSymbol.paramss.flatten.exists { p =>
        val paramAnnoMeta = Globals.metadataBuilder.getOrCreate(SymbolFacade(p))
        paramAnnoMeta.hasStereotype(ANN_CONSTRAINT) || paramAnnoMeta.hasStereotype(ANN_VALID)
      }) {
        hasConstraints = true
        methodAnnotationMetadata = Globals.metadataBuilder.annotate(methodAnnotationMetadata, io.micronaut.core.annotation.AnnotationValue.builder(ANN_VALIDATED).build)
      }

      if (isDeclaredBean && isExecutable && overridden.isEmpty) {
        visitExecutableMethod(methodSymbol, methodAnnotationMetadata, beanDefinitionWriter)
      } else if (isConfigurationPropertiesType && !methodSymbol.isPrivate && !methodSymbol.isStatic) {
        val methodName = methodSymbol.nameString
        if (NameUtils.isSetterName(methodName) && methodSymbol.paramss.size == 1) {
          visitConfigurationPropertySetter(methodSymbol, methodAnnotationMetadata, beanDefinitionWriter)
        } else if (NameUtils.isGetterName(methodName)) {
            if (!beanDefinitionWriter.isValidated) {
              beanDefinitionWriter.setValidated(
                methodAnnotationMetadata.hasStereotype(ANN_CONSTRAINT) ||
                methodAnnotationMetadata.hasStereotype(ANN_VALID))
            }
        }
      } else if (isPublic && hasConstraints) {
        visitExecutableMethod(methodSymbol, methodAnnotationMetadata, beanDefinitionWriter)
      }
    }
  }

  private def visitConfigurationPropertySetter(methodSymbol: Global#Symbol, methodAnnotationMetadata:AnnotationMetadata, beanDefinitionVisitor: BeanDefinitionVisitor): Unit = {
    val parameterSymbol = methodSymbol.paramss.head.head
    val fieldType = resolveType(parameterSymbol.originalInfo)
//    val fieldType: Any = Globals.argTypeForTree(valueType.)
    var genericTypes: util.Map[String, AnyRef] = Collections.emptyMap()
//    val typeKind: TypeKind = valueType.getKind

//    if (!(typeKind.isPrimitive || (typeKind eq ARRAY))) genericTypes = genericUtils.resolveGenericTypes(valueType, Collections.emptyMap)
    val declaringClass = methodSymbol.owner.fullName
    if (declaringClass != null) {
      val propertyName = NameUtils.getPropertyNameForSetter(methodSymbol.nameString)
      val isInterface = parameterSymbol.isInterface
      if (methodAnnotationMetadata.hasStereotype(classOf[ConfigurationBuilder])) {
          beanDefinitionVisitor.visitConfigBuilderMethod(fieldType, NameUtils.getterNameFor(propertyName), methodAnnotationMetadata, metadataBuilder, isInterface)
         // try visitConfigurationBuilder(declaringClass, method, valueType, beanDefinitionVisitor)
         // finally beanDefinitionVisitor.visitConfigBuilderEnd()
      } else {
//        if (shouldExclude(configurationMetadata, propertyName)) return
        val docComment: String = "" //elementUtils.getDocComment(method)
        val setterName: String = methodSymbol.nameString
        val propertyMetadata = metadataBuilder.visitProperty(
          concreteClass.symbol,
          methodSymbol.owner,
          getPropertyMetadataTypeReference(parameterSymbol).toString,
          propertyName, docComment, null)
        val annotationMetadata: AnnotationMetadata = addPropertyMetadata(AnnotationMetadata.EMPTY_METADATA, propertyMetadata)
        val requiresReflection = if (methodSymbol.isPublic) {
            false
          } else if (methodSymbol.hasPackageFlag || methodSymbol.isProtected) {
            val declaringPackage = methodSymbol.owner.packageObject
            val concretePackage = concreteClass.symbol.packageObject
            !(declaringPackage.fullName == concretePackage.fullName)
          } else {
            true
          }
        beanDefinitionVisitor.visitSetterValue(
          declaringClass,
          resolveType(methodSymbol.originalInfo.resultType),
          annotationMetadata,
          requiresReflection,
          fieldType,
          setterName,
          genericTypes,
          Globals.metadataBuilder.getOrCreate(SymbolFacade(parameterSymbol)),
          true)
      }
    }
  }

  private def getPropertyMetadataTypeReference(valueType: Global#Symbol) =
    //if (modelUtils.isOptional(valueType)) genericUtils.getFirstTypeArgument(valueType).map((typeMirror: TypeMirror) => modelUtils.resolveTypeName(typeMirror)).orElseGet(() => modelUtils.resolveTypeName(valueType))
    //else
    valueType

  private def addPropertyMetadata(annotationMetadata: AnnotationMetadata, propertyMetadata: PropertyMetadata) =
    DefaultAnnotationMetadata.mutateMember(
      annotationMetadata,
      classOf[PropertySource].getName,
      AnnotationMetadata.VALUE_MEMBER,
      Collections.singletonList(
        new AnnotationValue[Annotation](
          classOf[Property].getName,
          Collections.singletonMap[CharSequence, Object]("name", propertyMetadata.getPath))
      )
    )


  private def createFactoryBeanMethodWriterFor(methodSymbol: Global#Symbol, producedElement: Global#Type) = {
    val annotationMetadata = Globals.metadataBuilder.buildForParent(
      SymbolFacade(producedElement.typeSymbol),
      SymbolFacade(methodSymbol), true)
    val isInterface = producedElement.typeSymbol.isInterface
    val packageName = producedElement.typeSymbol.enclosingPackage.fullName
    val beanDefinitionPackage = methodSymbol.owner.enclosingPackage.fullName.toString
    val shortClassName = producedElement.typeSymbol.nameString
    val upperCaseMethodName = NameUtils.capitalize(methodSymbol.name.toString())
    val factoryMethodBeanDefinitionName = beanDefinitionPackage + ".$" + concreteClass.symbol.nameString + "$" + upperCaseMethodName + factoryMethodIndex.getAndIncrement + "Definition"

    new BeanDefinitionWriter(
      packageName,
      shortClassName,
      factoryMethodBeanDefinitionName,
      resolveType(methodSymbol.originalInfo.resultType).toString,
      isInterface,
      originatingElement,
      annotationMetadata)
  }

  private def visitBeanFactoryMethod(methodSymbol: Global#Symbol, methodAnnotationMetadata:AnnotationMetadata): Unit = {
//    if (isFactoryType && methodAnnotationMetadata.hasStereotype(beanMethod.symbol.owner, AROUND_TYPE)) {
//      visitExecutableMethod(beanMethod, annotationUtils.getAnnotationMetadata(beanMethod))
//    }
    val producedElement = methodSymbol.asInstanceOf[Global#MethodSymbol].originalInfo.resultType
    if (producedElement != null) {
      val beanMethodParams = ExecutableElementParamInfo.populateParameterData(methodSymbol) //, Collections.emptyMap)
      val beanMethodWriter = createFactoryBeanMethodWriterFor(methodSymbol, producedElement)

      val beanTypeArguments = new util.LinkedHashMap[String, util.Map[String, AnyRef]]()
      if (producedElement.typeSymbol.isClass || producedElement.typeSymbol.isInterface) {
        val genericTypeMap = typeGenericsForParamsAndArgs(
          producedElement.typeSymbol.originalInfo.typeParams,
          producedElement.typeArgs
        )
        if (!genericTypeMap.isEmpty) {
          beanTypeArguments.put(producedElement.typeSymbol.fullName, genericTypeMap)
          beanMethodWriter.visitTypeArguments(beanTypeArguments)
        }
      }

      val beanMethodName = methodSymbol.nameString
      val beanMethodParameters = beanMethodParams.parameters
      //val methodKey: StringBuilder = new StringBuilder(beanMethodName).append("(").append(beanMethodParameters.values.stream.map(_.toString).collect(Collectors.joining(","))).append(")")
      beanDefinitionWriters += SymbolFacade(methodSymbol) -> beanMethodWriter
      val beanMethodDeclaringType: Any = methodSymbol.owner.fullName
      val methodAnnotationMetadata: AnnotationMetadata = Globals.metadataBuilder.buildForParent(SymbolFacade(producedElement.typeSymbol), SymbolFacade(methodSymbol))
      beanMethodWriter.visitBeanFactoryMethod(
        beanMethodDeclaringType,
        resolveType(methodSymbol.originalInfo.resultType),
        beanMethodName,
        methodAnnotationMetadata,
        beanMethodParameters,
        beanMethodParams.parameterMetadata,
        beanMethodParams.genericTypes
      )
      //    if (methodAnnotationMetadata.hasStereotype(AROUND_TYPE) && !modelUtils.isAbstract(concreteClass)) {
      //      val interceptorTypes: Array[AnyRef] = methodAnnotationMetadata.getAnnotationNamesByStereotype(AROUND_TYPE).toArray
      //      val returnTypeElement: TypeElement = beanMethod.getReturnType.asInstanceOf[DeclaredType].asElement.asInstanceOf[TypeElement]
      //      if (modelUtils.isFinal(returnTypeElement)) {
      //        error(returnTypeElement, "Cannot apply AOP advice to final class. Class must be made non-final to support proxying: " + returnTypeElement)
      //        return
      //      }
      //      val constructor: ExecutableElement = if (JavaModelUtils.isClass(returnTypeElement)) modelUtils.concreteConstructorFor(returnTypeElement, annotationUtils)
      //      else null
      //      val constructorData: ExecutableElementParamInfo = if (constructor != null) populateParameterData(null, constructor, Collections.emptyMap)
      //      else null
      //      val aopSettings: OptionalValues[Boolean] = methodAnnotationMetadata.getValues(AROUND_TYPE, classOf[Boolean])
      //      val finalSettings: util.Map[CharSequence, Boolean] = new util.LinkedHashMap[CharSequence, Boolean]
      //      import scala.collection.JavaConversions._
      //      for (setting <- aopSettings) {
      //        val entry: Optional[Boolean] = aopSettings.get(setting)
      //        entry.ifPresent((`val`: Boolean) => finalSettings.put(setting, `val`))
      //      }
      //      finalSettings.put(Interceptor.PROXY_TARGET, true)
      //      val proxyWriter: AopProxyWriter = resolveAopProxyWriter(beanMethodWriter, OptionalValues.of(classOf[Boolean], finalSettings), true, constructorData, interceptorTypes)
      //      if (beanTypeArguments != null) proxyWriter.visitTypeArguments(beanTypeArguments)
      //      returnType.accept(new PublicMethodVisitor[AnyRef, AopProxyWriter](typeUtils) {
      //        override protected def accept(`type`: DeclaredType, element: Element, aopProxyWriter: AopProxyWriter): Unit = {
      //          val method: ExecutableElement = element.asInstanceOf[ExecutableElement]
      //          val owningType: Any = modelUtils.resolveTypeReference(method.getEnclosingElement)
      //          if (owningType == null) throw new IllegalStateException("Owning type cannot be null")
      //          val returnTypeMirror: TypeMirror = method.getReturnType
      //          val returnType: TypeMirror = method.getReturnType
      //          val returnTypeGenerics: util.Map[String, AnyRef] = new util.HashMap[String, AnyRef]
      //          genericUtils.resolveBoundGenerics(method.getEnclosingElement.asInstanceOf[TypeElement], returnType, genericUtils.buildGenericTypeArgumentElementInfo(concreteClass)).forEach((key: String, value: TypeMirror) => returnTypeGenerics.put(key, modelUtils.resolveTypeReference(value)))
      //          //Object resolvedReturnType = genericUtils.resolveTypeReference(returnType, returnTypeGenerics);
      //          val resolvedReturnType: Any = modelUtils.resolveTypeReference(returnType)
      //          val enclosingElement: TypeElement = method.getEnclosingElement.asInstanceOf[TypeElement]
      //          var boundTypes: util.Map[String, AnyRef] = genericUtils.buildGenericTypeArgumentInfo(concreteClass).get(enclosingElement.getQualifiedName.toString)
      //          if (boundTypes == null) boundTypes = Collections.emptyMap
      //          val params: ExecutableElementParamInfo = populateParameterData(null, method, boundTypes)
      //          val methodName: String = method.getSimpleName.toString
      //          val methodParameters: util.Map[String, AnyRef] = params.getParameters
      //          val methodQualifier: util.Map[String, AnnotationMetadata] = params.getParameterMetadata
      //          val methodGenericTypes: util.Map[String, util.Map[String, AnyRef]] = params.getGenericTypes
      //          val genericParameters: util.Map[String, AnyRef] = params.getGenericParameters
      //          var annotationMetadata: AnnotationMetadata = null
      //          var isAnnotationReference: Boolean = false
      //          // if the method is annotated we build metadata for the method
      //          if (annotationUtils.isAnnotated(producedTypeName, method)) annotationMetadata = annotationUtils.getAnnotationMetadata(beanMethod, method)
      //          else { // otherwise we setup a reference to the parent metadata (essentially the annotations declared on the bean factory method)
      //            isAnnotationReference = true
      //            annotationMetadata = new AnnotationMetadataReference(beanMethodWriter.getBeanDefinitionName + BeanDefinitionReferenceWriter.REF_SUFFIX, methodAnnotationMetadata)
      //          }
      //          val executableMethodWriter: ExecutableMethodWriter = beanMethodWriter.visitExecutableMethod(owningType, modelUtils.resolveTypeReference(returnTypeMirror), resolvedReturnType, returnTypeGenerics, methodName, methodParameters, genericParameters, methodQualifier, methodGenericTypes, annotationMetadata, JavaModelUtils.isInterface(method.getEnclosingElement), method.isDefault)
      //          aopProxyWriter.visitAroundMethod(owningType, resolvedReturnType, resolvedReturnType, returnTypeGenerics, methodName, methodParameters, genericParameters, methodQualifier, methodGenericTypes, if (!isAnnotationReference) new AnnotationMetadataReference(executableMethodWriter.getClassName, annotationMetadata)
      //          else annotationMetadata, JavaModelUtils.isInterface(method.getEnclosingElement), method.isDefault)
      //        }
      //      }, proxyWriter)
      //    }
      //    else
      if (methodAnnotationMetadata.hasStereotype(classOf[Executable])) {
        methodSymbol.originalInfo.resultType.typeSymbol.originalInfo.decls
          .filter { it: Global#Symbol => it.isMethod && !it.isConstructor }
          .foreach { subSymbol =>

            val owningType = subSymbol.owner
            val methodName = subSymbol.nameString

            val annotationMetadata: AnnotationMetadata = new AnnotationMetadataReference(beanMethodWriter.getBeanDefinitionName + BeanDefinitionReferenceWriter.REF_SUFFIX, methodAnnotationMetadata)
            val returnTypeGenerics = typeGenericsForParamsAndArgs(
              producedElement.typeSymbol.originalInfo.typeParams,
              producedElement.typeArgs
            )

            val params = ExecutableElementParamInfo.populateParameterData(
              Some(producedElement.typeSymbol.fullName),
              Some(subSymbol),
              returnTypeGenerics)
            val methodParameters = params.parameters
            val genericParameters = params.genericParameters
            val methodQualifier = params.parameterMetadata
            val methodGenericTypes = params.genericTypes

            val returnedType = subSymbol.typeOfThis.resultType.typeSymbol.fullName

            val resolvedReturnType = returnTypeGenerics.getOrDefault(
              subSymbol.originalInfo.resultType.typeSymbol.nameString,
              returnedType
            )

            beanMethodWriter.visitExecutableMethod(
              owningType.fullName,
              returnedType,
              resolvedReturnType,
              returnTypeGenerics,
              methodName,
              methodParameters,
              genericParameters,
              methodQualifier,
              methodGenericTypes,
              annotationMetadata,
              methodSymbol.enclClass.isInterface,
              false) // TODO .isDefault)
          }
      }
     //returnType.accept(new PublicMethodVisitor[AnyRef, BeanDefinitionWriter](typeUtils) {
      //      override protected def accept(`type`: DeclaredType, element: Element, beanWriter: BeanDefinitionWriter): Unit = {
      //        val method: ExecutableElement = element.asInstanceOf[ExecutableElement]
      //        val owningType: Any = modelUtils.resolveTypeReference(method.getEnclosingElement)
      //        if (owningType == null) throw new IllegalStateException("Owning type cannot be null")
      //        val returnTypeMirror: TypeMirror = method.getReturnType
      //        val methodName: String = method.getSimpleName.toString
      //        val annotationMetadata: AnnotationMetadata = new AnnotationMetadataReference(beanMethodWriter.getBeanDefinitionName + BeanDefinitionReferenceWriter.REF_SUFFIX, methodAnnotationMetadata)
      //        val returnTypeGenerics: util.Map[String, AnyRef] = new util.HashMap[String, AnyRef]
      //        genericUtils.resolveBoundGenerics(method.getEnclosingElement.asInstanceOf[TypeElement], returnType, genericUtils.buildGenericTypeArgumentElementInfo(`type`.asElement)).forEach((key: String, value: TypeMirror) => returnTypeGenerics.put(key, modelUtils.resolveTypeReference(value)))
      //        val enclosingElement: TypeElement = method.getEnclosingElement.asInstanceOf[TypeElement]
      //        var boundTypes: util.Map[String, AnyRef] = genericUtils.buildGenericTypeArgumentInfo(`type`).get(enclosingElement.getQualifiedName.toString)
      //        if (boundTypes == null) boundTypes = Collections.emptyMap
      //        val resolvedReturnType: Any = genericUtils.resolveTypeReference(returnTypeMirror, boundTypes)
      //        val params: ExecutableElementParamInfo = populateParameterData(producedTypeName, method, boundTypes)
      //        val methodParameters: util.Map[String, AnyRef] = params.getParameters
      //        val genericParameters: util.Map[String, AnyRef] = params.getGenericParameters
      //        val methodQualifier: util.Map[String, AnnotationMetadata] = params.getParameterMetadata
      //        val methodGenericTypes: util.Map[String, util.Map[String, AnyRef]] = params.getGenericTypes
      //        beanMethodWriter.visitExecutableMethod(owningType, modelUtils.resolveTypeReference(returnTypeMirror), resolvedReturnType, returnTypeGenerics, methodName, methodParameters, genericParameters, methodQualifier, methodGenericTypes, annotationMetadata, JavaModelUtils.isInterface(enclosingElement), method.isDefault)
      //      }
      //    }, beanMethodWriter)
      //    if (methodAnnotationMetadata.isPresent(classOf[Bean], "preDestroy")) {
      //      val preDestroyMethod: Optional[String] = methodAnnotationMetadata.getValue(classOf[Bean], "preDestroy", classOf[String])
      //      preDestroyMethod.ifPresent((destroyMethodName: String) => {
      //        def foo(destroyMethodName: String) = if (StringUtils.isNotEmpty(destroyMethodName)) {
      //          val destroyMethodDeclaringClass: TypeElement = typeUtils.asElement(returnType).asInstanceOf[TypeElement]
      //          val destroyMethodRef: Optional[ExecutableElement] = modelUtils.findAccessibleNoArgumentInstanceMethod(destroyMethodDeclaringClass, destroyMethodName)
      //          if (destroyMethodRef.isPresent) beanMethodWriter.visitPreDestroyMethod(destroyMethodDeclaringClass.getQualifiedName.toString, genericUtils.resolveTypeReference(destroyMethodRef.get.getReturnType), destroyMethodName)
      //          else error(beanMethod, "@Bean method defines a preDestroy method that does not exist or is not public: " + destroyMethodName)
      //        }
      //
      //        foo(destroyMethodName)
      //      })
      //    }
    }
  }

  def resolveType(tpe:Global#Type): AnyRef = {
    if (tpe.typeSymbol.isTypeParameter) {
      tpe.typeSymbol.originalInfo.bounds.hi.typeSymbol.fullName
    } else {
      TypeFunctions.argTypeForTypeSymbol(
        tpe.typeSymbol,
        tpe.typeArgs
      )
    }
  }

  def visitVariable(valSymbol: Global#Symbol, beanDefinitionWriter:BeanDefinitionVisitor):Unit = { // assuming just fields, visitExecutable should be handling params for method calls
    val fieldAnnotationMetadata = Globals.metadataBuilder.getOrCreate(SymbolFacade(valSymbol))

    val isInjected = fieldAnnotationMetadata.hasStereotype(classOf[Inject])
    val isValue = !isInjected && (fieldAnnotationMetadata.hasStereotype(classOf[Value]) || fieldAnnotationMetadata.hasStereotype(classOf[Property]))
    if (isInjected || isValue) {

      val declaringClass = valSymbol.enclClass

      val isPrivate = valSymbol.isPrivate
      val requiresReflection = isPrivate || isInheritedAndNotPublic(this.concreteClass.symbol, declaringClass, valSymbol);

      val genericTypeMap = TypeFunctions.genericTypesForSymbol(
        valSymbol
      )

      val argType = resolveType(valSymbol.originalInfo)

      if (isValue) {
        beanDefinitionWriter.visitFieldValue(
          concreteClass.symbol.fullName,
          argType,
          valSymbol.nameString,
          requiresReflection,
          fieldAnnotationMetadata,
          genericTypeMap,
          false)
      } else {
        beanDefinitionWriter.visitFieldInjectionPoint(
          concreteClass.symbol.fullName,
          argType,
          valSymbol.nameString,
          requiresReflection,
          fieldAnnotationMetadata,
          genericTypeMap
        )
      }
    }
  }

  def visitSymbol(symbolToOverrides:mutable.Map[Global#Symbol, Global#Symbol], beanDefinitionWriter:BeanDefinitionVisitor, doVisit:Boolean)(symbol:Global#Symbol):Unit = {
    symbol match {
      case methodSymbol: Global#MethodSymbol if methodSymbol.isMethod &&
        !(methodSymbol.isConstructor || methodSymbol.isAccessor || methodSymbol.isBridge) =>
      {
          if (doVisit) {
            visitExecutable(methodSymbol, beanDefinitionWriter, symbolToOverrides.get(methodSymbol))
          } else {
            val overridden = methodSymbol.overrides
            overridden.foreach(ancestor => if (!symbolToOverrides.contains(ancestor)) symbolToOverrides.put(ancestor, methodSymbol))
            val bridgeOverrides = Globals.methodsToBridgeOverrides.getOrElse(methodSymbol, List())
            bridgeOverrides.foreach(ancestor => if (!symbolToOverrides.contains(ancestor)) symbolToOverrides.put(ancestor, methodSymbol))
          }
      }
      case termSymbol: Global#TermSymbol if termSymbol.isMutable /* && !termSymbol.originalInfo.typeSymbol.isAbstract */ => {
        if (doVisit) {
          visitVariable(termSymbol, beanDefinitionWriter)
        }
      }
      case _ => ()
    }
  }

  def visit(): Unit = {
    if (!concreteClass.symbol.isAbstract || isDeclaredBean) {
      val concreteClassMetadata = Globals.metadataBuilder.getOrCreate(SymbolFacade(concreteClass.symbol))

      val beanDefinitionWriter = new BeanDefinitionWriter(
        concreteClass.symbol.enclosingPackageClass.fullName,
        concreteClass.name.toString,
        originatingElement,
        concreteClassMetadata
      )

      val beanDefinitionReferenceWriter = new BeanDefinitionReferenceWriter(
        beanDefinitionWriter.getBeanTypeName,
        beanDefinitionWriter.getBeanDefinitionName,
        beanDefinitionWriter.getOriginatingElement,
        beanDefinitionWriter.getAnnotationMetadata
      )

      val annotationMetadata = new AnnotationMetadataHierarchy(concreteClassMetadata)

      beanDefinitionWriter.visitBeanDefinitionConstructor(
        annotationMetadata,
        false,
        constructorParameterInfo.parameters,
        constructorParameterInfo.parameterMetadata,
        constructorParameterInfo.genericTypes
      )

      visitType(concreteClass, Globals.metadataBuilder.getOrCreate(SymbolFacade(concreteClass.symbol)))

      visitTypeArguments(concreteClass.symbol, beanDefinitionWriter)

      val symbolToLowestDescendant = new mutable.HashMap[Global#Symbol, Global#Symbol]()

      concreteClass.impl.body.map(_.symbol).foreach(visitSymbol(symbolToLowestDescendant, beanDefinitionWriter, false)(_))

      concreteClass.symbol.baseClasses.tail.filter(filterAncestors)
        .foreach((classSymbol:Global#Symbol) => classSymbol.originalInfo.decls.foreach(visitSymbol(symbolToLowestDescendant, beanDefinitionWriter, false)(_)))

      concreteClass.symbol.baseClasses.tail.reverse.filter(filterAncestors)
        .foreach((classSymbol:Global#Symbol) => classSymbol.originalInfo.decls.foreach(visitSymbol(symbolToLowestDescendant, beanDefinitionWriter, true)(_)))

      concreteClass.impl.body.map(_.symbol).foreach(visitSymbol(symbolToLowestDescendant, beanDefinitionWriter, true)(_))

      beanDefinitionWriter.visitBeanDefinitionEnd()
      beanDefinitionWriter.accept(visitorContext)

      beanDefinitionReferenceWriter.accept(visitorContext)
    }
  }

  def filterAncestors(symbol:Global#Symbol) = !FILTERED_ANCESTORS.contains(symbol.fullName)

  def visitTypeArguments(typeSymbol:Global#Symbol, beanDefinitionWriter:BeanDefinitionWriter):Unit = {
    val typeArguments = new util.LinkedHashMap[String, util.Map[String, AnyRef]]()
    typeSymbol.baseClasses.filter(filterAncestors).foreach { classSymbol =>
        val typeCtor = classSymbol.originalInfo.typeConstructor

        typeCtor.parents.foreach { parent =>
          val parentSymbol = parent.typeSymbol
          val args = parent.typeArgs
          val params = parent.typeSymbol.originalInfo.typeParams

          (args, params).zipped.foreach { (arg, param) =>
            arg match {
              case absTypeRef: Global#AbstractTypeRef => {
                val asStr = absTypeRef.toString()
                val valueList = typeArguments.values().toList
                valueList.foreach { otherMap =>
                  if (otherMap.containsKey(asStr)) {
                    typeArguments.computeIfAbsent(parentSymbol.fullName, _ => new util.LinkedHashMap[String, AnyRef]())
                      .put(param.nameString, otherMap.get(asStr))
                  }
                }
              }
              case typeRef: Global#ArgsTypeRef => {
                typeArguments.computeIfAbsent(parentSymbol.fullName, _ => new util.LinkedHashMap[String, AnyRef]())
                  .put(param.nameString, argTypeForTypeSymbol(typeRef.sym, typeRef.args))
              }
              case _ => {
                typeArguments.computeIfAbsent(parentSymbol.fullName, _ => new util.LinkedHashMap[String, AnyRef]())
                  .put(param.nameString, argTypeForTypeSymbol(arg.typeSymbol, arg.typeArgs))
              }
            }
          }
        }
    }
    if (!typeArguments.isEmpty) {
      beanDefinitionWriter.visitTypeArguments(typeArguments);
    }
  }
}
