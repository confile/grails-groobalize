package com.ticketbis.groobalize.ast

import groovy.util.logging.Log4j
import groovy.transform.CompilationUnitAware
import org.codehaus.groovy.transform.ASTTransformation
import org.codehaus.groovy.transform.GroovyASTTransformation
import org.codehaus.groovy.control.CompilePhase
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.grails.commons.GrailsClassUtils
import org.codehaus.groovy.grails.commons.GrailsDomainClassProperty
import org.codehaus.groovy.grails.compiler.injection.GrailsASTUtils
import org.codehaus.groovy.ast.builder.AstBuilder

import org.codehaus.groovy.ast.*
import org.codehaus.groovy.ast.expr.*
import org.codehaus.groovy.ast.stmt.*
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.transform.trait.TraitComposer

import com.ticketbis.groobalize.Translation
import com.ticketbis.groobalize.Translated
import com.ticketbis.groobalize.GroobalizeHelper

@Log4j
@GroovyASTTransformation(phase = CompilePhase.CANONICALIZATION)
class TranslatableTransformation implements ASTTransformation, CompilationUnitAware {

    CompilationUnit compilationUnit

    private final static NON_TRANSLATABLE_FIELDS = [
        'id', 'version', 'errors', 'metaClass', 'lastUpdated', 'dateCreated'
    ]

    private static final ClassNode TRANSLATED_NODE = new ClassNode(Translated)

    void visit(ASTNode[] astNodes, SourceUnit sourceUnit) {
        assert astNodes[0] instanceof AnnotationNode
        assert astNodes[1] instanceof ClassNode,
                "@Translatable can only be applied on classes"

        AnnotationNode annotation = astNodes[0]
        ClassNode translatableClassNode = astNodes[1]

        assert annotation.members['with'] instanceof ClassExpression,
                "Invalid translation class specified."

        ClassExpression translateWithExpr = annotation.members['with']
        ClassNode translateWithClass = translateWithExpr.type

        assert GrailsASTUtils.isDomainClass(translatableClassNode, sourceUnit),
                "@Translatable annotation should be applied over domain class"

        assert GrailsASTUtils.isDomainClass(translateWithClass, sourceUnit),
                "Translation class should be a domain class"

        log.info "Adding Translatable transform to ${ translatableClassNode.name }..."

        addTranslationClassInfo(translatableClassNode, translateWithClass)
        addHasManyTranslations(translatableClassNode, translateWithClass)
        addNamedQueries(translatableClassNode)
        addProxyGetters(translatableClassNode, translateWithClass)
        addTranslatedTrait(translatableClassNode, sourceUnit)
    }

    /**
     * Adds class info and translatable fields as static fields. E.g:.
     * ```
     * static final Class translationClass = BookTranslation
     * static final List translatedFields = ['title', 'description']
     * ```
     */
    private void addTranslationClassInfo(ClassNode classNode, ClassNode translationClass) {
        // Add translationClass static field
        GroobalizeASTUtils.getOrCreateStaticField(
            classNode,
            'translationClass',
            new ClassExpression(translationClass),
            FieldNode.ACC_FINAL,
            Class)

        // Add translatedFields static field
        List<FieldNode> translatedFields = getTranslatableFields(translationClass)
        ArrayExpression initialArrayExpr = new ArrayExpression(
            new ClassNode(String),
            translatedFields.collect { new ConstantExpression(it.name) } as List<Expression>)

        GroobalizeASTUtils.getOrCreateStaticField(
            classNode,
            'translatedFields',
            initialArrayExpr,
            FieldNode.ACC_FINAL,
            List)
    }

    private void addHasManyTranslations(ClassNode classNode, ClassNode translationClass) {
        GroobalizeASTUtils.addHasManyRelationship(classNode, 'translations', translationClass)
    }

    private void addNamedQueries(ClassNode classNode) {
        Statement code = new AstBuilder().buildFromCode {
            includeTranslations { Collection<Locale> locales = null ->
                withTranslations(locales)
            }
            translated { withDefaultTranslations() }
        }.pop()
        GroobalizeASTUtils.addNamedQuery(classNode, code)
    }

    private void addProxyGetters(ClassNode classNode, ClassNode translationClass) {
        List<FieldNode> translatableFields = getTranslatableFields(translationClass)
        translatableFields.each { FieldNode field ->
            List<AnnotationNode> fieldAnnotations =
                field.getAnnotations(new ClassNode(Field))

            boolean inherit = true
            boolean skipGetter = false

            if (fieldAnnotations) {
                AnnotationNode annotation = fieldAnnotations.first()
                if (annotation.members['inherit'])
                    inherit = annotation.members['inherit'].isTrueExpression()
                if (annotation.members['skipGetter'])
                    skipGetter = annotation.members['skipGetter'].isTrueExpression()
            }
            if (skipGetter)
                return

            String fieldName = field.name
            String getterName = GrailsClassUtils.getGetterName(fieldName)

            // Added getter without parameters
            BlockStatement getterCode = new BlockStatement([
                new ReturnStatement(
                    new StaticMethodCallExpression(
                        new ClassNode(GroobalizeHelper),
                        'getField',
                        new ArgumentListExpression([
                            new VariableExpression('translations'),
                            new ConstantExpression(fieldName),
                            new ConstantExpression(inherit)
                        ] as Expression[])
                    )
                )
            ] as Statement[],
            new VariableScope())

            def methodNode = new MethodNode(
                    getterName,
                    FieldNode.ACC_PUBLIC,
                    field.type,
                    Parameter.EMPTY_ARRAY,
                    ClassHelper.EMPTY_TYPE_ARRAY,
                    getterCode)

            classNode.addMethod(methodNode)

            // Add getter for a given localeContext
            BlockStatement getterCodeWithContext = new BlockStatement([
                new ReturnStatement(
                    new StaticMethodCallExpression(
                        new ClassNode(GroobalizeHelper),
                        'getField',
                        new ArgumentListExpression([
                            new VariableExpression('translations'),
                            new ConstantExpression(fieldName),
                            new ConstantExpression(inherit),
                            new VariableExpression('localeContext')
                        ] as Expression[])
                    )
                )
            ] as Statement[],
            new VariableScope())


            ClassNode localeContext = new ClassNode(org.springframework.context.i18n.LocaleContext)
            methodNode = new MethodNode(
                    getterName,
                    FieldNode.ACC_PUBLIC,
                    field.type,
                    [new Parameter(localeContext, 'localeContext')] as Parameter[],
                    ClassHelper.EMPTY_TYPE_ARRAY,
                    getterCodeWithContext)

            classNode.addMethod(methodNode)

            // Make it transient
            GroobalizeASTUtils.addTransient(classNode, fieldName)
        }
    }

    private void addTranslatedTrait(ClassNode classNode, SourceUnit sourceUnit) {
        if (classNode.declaresInterface(TRANSLATED_NODE))
            return

        classNode.addInterface(TRANSLATED_NODE)
        TraitComposer.doExtendTraits(classNode, sourceUnit, compilationUnit)

        GroobalizeASTUtils.addTransient(classNode, 'translationsMapCache')
        GroobalizeASTUtils.addTransient(classNode, 'translationByLocale')
    }

    private List<FieldNode> getTranslatableFields(ClassNode translationClass) {
        translationClass.fields.findAll {
            !(it.modifiers & FieldNode.ACC_STATIC) &&
            !(it.name in NON_TRANSLATABLE_FIELDS)
        }
    }

}
