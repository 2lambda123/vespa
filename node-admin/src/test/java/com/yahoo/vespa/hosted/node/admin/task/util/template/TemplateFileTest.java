// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.node.admin.task.util.template;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author hakonhall
 */
class TemplateFileTest {
    @Test
    void verifyVariableSection() {
        Form form = getForm("template1.tmp");
        form.set("varname", "varvalue");
        assertEquals("variable section 'varvalue'\n" +
                     "end of text\n", form.render());
    }

    @Test
    void verifySimpleListSection() {
        Form form = getForm("template1.tmp");
        form.set("varname", "varvalue")
            .add("listname")
            .set("varname", "different varvalue")
            .set("varname2", "varvalue2");
        assertEquals("variable section 'varvalue'\n" +
                     "same variable section 'different varvalue'\n" +
                     "different variable section 'varvalue2'\n" +
                     "between ends\n" +
                     "end of text\n", form.render());
    }

    @Test
    void verifyNestedListSection() {
        Form form = getForm("template2.tmp");
        Form A0 = form.add("listA");
        Form A0B0 = A0.add("listB");
        Form A0B1 = A0.add("listB");

        Form A1 = form.add("listA");
        Form A1B0 = A1.add("listB");
        assertEquals("body A\n" +
                     "body B\n" +
                     "body B\n" +
                     "body A\n" +
                     "body B\n",
                     form.render());
    }

    @Test
    void verifyVariableReferences() {
        Form form = getForm("template3.tmp");
        form.set("varname", "varvalue")
            .set("innerVarSetAtTop", "val2");
        form.add("l");
        form.add("l")
            .set("varname", "varvalue2");
        assertEquals("varvalue\n" +
                     "varvalue\n" +
                     "inner varvalue\n" +
                     "val2\n" +
                     "inner varvalue2\n" +
                     "val2\n",
                     form.render());
    }

    @Test
    void verifyNewlineRemoval() {
        Form form = makeForm("a%{list a}\n" +
                             "b%{end}\n" +
                             "c%{list c-}\n" +
                             "d%{end-}\n" +
                             "e\n");
        form.add("a");
        form.add("c");

        assertEquals("a\n" +
                     "b\n" +
                     "cde\n",
                     form.render());
    }

    @Test
    void verifyIfSection() {
        Template template = Template.from("Hello%{if cond} world%{end}!");
        assertEquals("Hello world!", template.instantiate().set("cond", true).render());
        assertEquals("Hello!", template.instantiate().set("cond", false).render());
    }

    @Test
    void verifyComplexIfSection() {
        Template template = Template.from("%{if cond-}\n" +
                                          "var: %{=varname}\n" +
                                          "if: %{if !inner}inner is false%{end}\n" +
                                          "list: %{list formname}element%{end}\n" +
                                          "%{end-}\n");

        assertEquals("", template.instantiate().set("cond", false).render());

        assertEquals("var: varvalue\n" +
                     "if: \n" +
                     "list: \n",
                     template.instantiate()
                             .set("cond", true)
                             .set("varname", "varvalue")
                             .set("inner", true)
                             .render());

        Form form = template.instantiate()
                            .set("cond", true)
                            .set("varname", "varvalue")
                            .set("inner", false);
        form.add("formname");

        assertEquals("var: varvalue\n" +
                     "if: inner is false\n" +
                     "list: element\n", form.render());
    }

    @Test
    void verifyElse() {
        var template = Template.from("%{if cond-}\n" +
                                     "if body\n" +
                                     "%{else-}\n" +
                                     "else body\n" +
                                     "%{end-}\n");
        assertEquals("if body\n", template.instantiate().set("cond", true).render());
        assertEquals("else body\n", template.instantiate().set("cond", false).render());
    }

    private Form getForm(String filename) {
        return TemplateFile.read(Path.of("src/test/resources/" + filename)).instantiate();
    }

    private Form makeForm(String templateText) {
        return Template.from(templateText).instantiate();
    }
}