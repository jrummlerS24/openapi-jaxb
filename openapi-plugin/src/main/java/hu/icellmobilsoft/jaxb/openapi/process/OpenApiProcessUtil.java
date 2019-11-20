package hu.icellmobilsoft.jaxb.openapi.process;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.List;
import java.util.logging.Logger;

import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.sun.codemodel.JAnnotatable;
import com.sun.codemodel.JAnnotationArrayMember;
import com.sun.codemodel.JAnnotationUse;
import com.sun.codemodel.JDefinedClass;
import com.sun.codemodel.JFieldVar;
import com.sun.codemodel.JMethod;
import com.sun.codemodel.JType;
import com.sun.tools.xjc.model.CClassInfo;
import com.sun.tools.xjc.model.CPropertyInfo;
import com.sun.tools.xjc.model.CTypeInfo;
import com.sun.tools.xjc.outline.EnumConstantOutline;
import com.sun.tools.xjc.outline.EnumOutline;
import com.sun.tools.xjc.reader.xmlschema.bindinfo.BindInfo;
import com.sun.xml.xsom.XSComponent;
import com.sun.xml.xsom.XSElementDecl;
import com.sun.xml.xsom.XSFacet;
import com.sun.xml.xsom.XSParticle;
import com.sun.xml.xsom.XSSimpleType;
import com.sun.xml.xsom.XSTerm;

import be.redlab.jaxb.swagger.XJCHelper;
import be.redlab.jaxb.swagger.process.AbstractProcessUtil;

import hu.icellmobilsoft.jaxb.openapi.constants.SchemaFields;

/**
 * ProcessUtil used for annotating classes with OpenApi {@link Schema}
 *
 * @author mark.petrenyi
 */
public class OpenApiProcessUtil extends AbstractProcessUtil {

    private static final OpenApiProcessUtil INSTANCE = new OpenApiProcessUtil();
    public static final String ENUMERATION_VALUES = "\n Enumeration values:\n";
    private Logger log = Logger.getLogger(OpenApiProcessUtil.class.getName());

    protected OpenApiProcessUtil() {
    }

    /**
     * Adding {@link Schema} annotation to fields by processing fields
     *
     * @param implClass
     * @param targetClass
     * @param jFieldVar
     * @param enums
     */
    public void addAnnotationForField(JDefinedClass implClass, CClassInfo targetClass, JFieldVar jFieldVar, Collection<EnumOutline> enums) {
        internalAddFieldAnnotation(implClass, targetClass, jFieldVar, isRequired(jFieldVar), getDefault(jFieldVar), enums);
    }

    /**
     * Adding {@link Schema} annotation to fields by processing methods (getters/setters)
     *
     * @param implClass
     * @param targetClass
     * @param method
     * @param required
     * @param defaultValue
     * @param enums
     */
    public void addAnnotationForMethod(JDefinedClass implClass, CClassInfo targetClass, JMethod method, boolean required, String defaultValue,
            Collection<EnumOutline> enums) {
        JFieldVar field = super.getCorrespondingField(implClass, method.name());
        if (field != null) {
            internalAddFieldAnnotation(implClass, targetClass, field, required, defaultValue, enums);
        }
    }

    /**
     * Checks if {@link Schema} annotation is not present on annotable (ie. field or method)
     *
     * @param annotatable
     * @return true if annotable is not annotated with {@link Schema} <br>
     *         false otherwise
     */
    @Override
    public boolean isAnnotationNotPresent(JAnnotatable annotatable) {
        return null == XJCHelper.getAnnotation(annotatable.annotations(), Schema.class);
    }

    public static OpenApiProcessUtil getInstance() {
        return INSTANCE;
    }

    /**
     * Annotates the input field with {@Schema} based on information provided by targetClass
     *
     * @param implClass
     * @param targetClass
     * @param field
     * @param required
     * @param defaultValue
     * @param enums
     */
    protected void internalAddFieldAnnotation(final JDefinedClass implClass, CClassInfo targetClass, final JFieldVar field, final boolean required,
            final String defaultValue, final Collection<EnumOutline> enums) {
        JAnnotationUse apiProperty = field.annotate(Schema.class);
        String name = field.name();
        apiProperty.param(SchemaFields.NAME, name);
        apiProperty.param(SchemaFields.TITLE, name);
        addArrayProperties(apiProperty, targetClass, name);
        String description = getDescription(targetClass, name);
        String className = getClassName(targetClass, field, name);
        EnumOutline eo = getKnownEnum(className, enums);
        if (null != eo) {
            addEnumeration(apiProperty, eo);
            String enumConstants = getEnumConstantDescription(eo);
            apiProperty.param(SchemaFields.DESCRIPTION, description + "\n" + enumConstants);
        } else {
            apiProperty.param(SchemaFields.DESCRIPTION, description);
        }
        if (required) {
            apiProperty.param(SchemaFields.REQUIRED, true);
        }
        if (null != defaultValue) {
            apiProperty.param(SchemaFields.DEFAULT_VALUE, defaultValue);
        }
        addRestrictions(apiProperty, targetClass, name);
    }

    /**
     * Some OpenAPI implementations does not include {@link Schema#enumeration()} into openapi yaml, therefore this creates a CommonMark syntax string
     * containing the list of enum constants extended with their respective <xs:documentation>
     *
     * @param eo
     * @return
     */
    private String getEnumConstantDescription(EnumOutline eo) {
        StringBuilder constantDescription = new StringBuilder();
        List<EnumConstantOutline> constants = eo.constants;
        if (constants != null && !constants.isEmpty()) {
            constantDescription.append(ENUMERATION_VALUES);
            for (EnumConstantOutline eco : constants) {
                constantDescription.append("* ");
                // gets the actual lexical value defined in the xsd instead of the enum name, since it can be escaped in java (ie. xsd: example.test
                // vs java enum:EXAMPLE_TEST)
                String enumName = eco.target.getLexicalValue();
                constantDescription.append("**").append(enumName).append("**");
                if (eco.target.getSchemaComponent() != null && eco.target.getSchemaComponent().getAnnotation() != null
                        && eco.target.getSchemaComponent().getAnnotation().getAnnotation() != null) {
                    Object annotationObj = eco.target.getSchemaComponent().getAnnotation().getAnnotation();
                    if (annotationObj != null && annotationObj instanceof BindInfo) {
                        String enumDocumentation = ((BindInfo) annotationObj).getDocumentation();
                        if (enumDocumentation != null) {
                            constantDescription.append(" - ").append(enumDocumentation);
                        }
                    }
                }
                constantDescription.append("\n");
            }
        }
        return constantDescription.toString();
    }

    /**
     * Fills apiProperty with the constants provided by eo ({@link EnumOutline#constants}).
     *
     * @param apiProperty
     *            must be a representation of {@Schema}, constants will be placed into {@link Schema#enumeration()}
     * @param eo
     */
    public static void addEnumeration(JAnnotationUse apiProperty, EnumOutline eo) {
        if (apiProperty != null && eo != null) {
            List<EnumConstantOutline> constants = eo.constants;
            if (constants != null && !constants.isEmpty()) {
                JAnnotationArrayMember paramArray = apiProperty.paramArray(SchemaFields.ENUMERATION);
                for (EnumConstantOutline eco : constants) {
                    // gets the actual lexical value defined in the xsd instead of the enum name, since it can be escaped in java (ie. xsd:
                    // example.test vs java enum:EXAMPLE_TEST)
                    String enumName = eco.target.getLexicalValue();
                    paramArray.param(enumName);
                }
            }
        }
    }

    private String getClassName(CClassInfo targetClass, JFieldVar field, String fieldName) {
        JType type = field.type();
        String className = type.fullName();
        CPropertyInfo property = targetClass.getProperty(fieldName);
        if (property.isCollection()) {
            // Collection<Enum<>>
            Collection<? extends CTypeInfo> refTypes = property.ref();
            if (refTypes != null && refTypes.size() == 1) {
                for (CTypeInfo refType : refTypes) {
                    if (refType.getType() != null) {
                        className = refType.getType().fullName();
                    }
                }

            }
        }
        return className;
    }

    /**
     * Obtains the given property from targetClass using propertyName, if its a collection property {@link Schema#type()} is set to
     * {@link SchemaType#ARRAY}, additionally {@link Schema#maxItems()}, {@link Schema#minItems()} is set if the collection is bounded.
     *
     * @param apiProperty
     *            must be a representation of {@Schema}
     * @param targetClass
     *            used to obtain {@link CPropertyInfo} with the given propertyName
     * @param propertyName
     */
    private void addArrayProperties(JAnnotationUse apiProperty, CClassInfo targetClass, String propertyName) {
        CPropertyInfo property = targetClass.getProperty(propertyName);
        if (property.isCollection()) {
            apiProperty.param(SchemaFields.TYPE, SchemaType.ARRAY);
            XSComponent schemaComponent = property.getSchemaComponent();
            if (schemaComponent instanceof XSParticle) {
                XSParticle particle = (XSParticle) schemaComponent;
                BigInteger maxOccurs = particle.getMaxOccurs();
                if (maxOccurs != null && XSParticle.UNBOUNDED != maxOccurs.intValue()) {
                    apiProperty.param(SchemaFields.MAX_ITEMS, maxOccurs.intValue());
                }

                BigInteger minOccurs = particle.getMinOccurs();
                if (minOccurs != null) {
                    apiProperty.param(SchemaFields.MIN_ITEMS, minOccurs.intValue());
                }
            }
        }
    }

    /**
     * Adds <xs:restriction>-s to {@link Schema} (ie."pattern", "length" etc.)
     *
     * @param apiProperty
     * @param targetClass
     * @param propertyName
     */
    private void addRestrictions(JAnnotationUse apiProperty, CClassInfo targetClass, String propertyName) {
        CPropertyInfo property = targetClass.getProperty(propertyName);
        XSComponent schemaComponent = property.getSchemaComponent();
        if (schemaComponent instanceof XSParticle) {
            XSParticle particle = (XSParticle) schemaComponent;
            XSTerm xsTerm = particle.getTerm();
            if (xsTerm != null && xsTerm instanceof XSElementDecl && ((XSElementDecl) xsTerm).getType() != null
                    && ((XSElementDecl) xsTerm).getType().asSimpleType() != null) {
                XSSimpleType xsSimpleType = ((XSElementDecl) xsTerm).getType().asSimpleType();
                addLength(apiProperty, xsSimpleType);
                addExtremum(apiProperty, xsSimpleType, true);
                addExtremum(apiProperty, xsSimpleType, false);
                String pattern = getFacetAsString(xsSimpleType, XSFacet.FACET_PATTERN);
                if (pattern != null) {
                    apiProperty.param(SchemaFields.PATTERN, pattern);
                }
            }
        }
    }

    private void addLength(JAnnotationUse apiProperty, XSSimpleType xsSimpleType) {
        Integer length = getFacetAsInteger(xsSimpleType, XSFacet.FACET_LENGTH);
        Integer maxLength = null;
        Integer minLength = null;
        if (length != null) {
            maxLength = length;
            minLength = length;
        } else {

            maxLength = getFacetAsInteger(xsSimpleType, XSFacet.FACET_MAXLENGTH);
            minLength = getFacetAsInteger(xsSimpleType, XSFacet.FACET_MINLENGTH);
        }
        if (maxLength != null) {
            apiProperty.param(SchemaFields.MAX_LENGTH, maxLength);
        }
        if (minLength != null) {
            apiProperty.param(SchemaFields.MIN_LENGTH, minLength);
        }
    }

    private void addExtremum(JAnnotationUse apiProperty, XSSimpleType xsSimpleType, boolean isMaximum) {
        String extremum = getFacetAsString(xsSimpleType, isMaximum ? XSFacet.FACET_MAXEXCLUSIVE : XSFacet.FACET_MINEXCLUSIVE);
        try {
            Boolean exlusive = true;
            if (extremum == null) {
                exlusive = false;
                extremum = getFacetAsString(xsSimpleType, isMaximum ? XSFacet.FACET_MAXINCLUSIVE : XSFacet.FACET_MININCLUSIVE);
            }
            if (extremum != null) {
                BigDecimal value = new BigDecimal(extremum);
                apiProperty.param(isMaximum ? SchemaFields.MAXIMUM : SchemaFields.MINIMUM, extremum);
                apiProperty.param(isMaximum ? SchemaFields.EXCLUSIVE_MAXIMUM : SchemaFields.EXCLUSIVE_MINIMUM, exlusive);
            }
        } catch (NumberFormatException e) {
            log.warning(String.format("Extremum: [%s] could not be set for SimpleType:[%s], since it cannot be parsed as BigDecimal!", extremum,
                    xsSimpleType));
        }
    }

    private Integer getFacetAsInteger(XSSimpleType xsSimpleType, String facetName) {
        String stringValue = getFacetAsString(xsSimpleType, facetName);
        if (stringValue != null) {
            try {
                return Integer.valueOf(stringValue);
            } catch (NumberFormatException e) {
                log.warning(
                        String.format("Could not obtain facet : [%s]=[%s] as Integer from SimpleType:[%s]!", facetName, stringValue, xsSimpleType));
                return null;
            }
        }
        return null;
    }

    private String getFacetAsString(XSSimpleType xsSimpleType, String facetName) {
        XSFacet facet = xsSimpleType.getFacet(facetName);
        String value = null;
        if (facet != null) {
            value = facet.getValue().value;
        }
        return value;
    }

}
