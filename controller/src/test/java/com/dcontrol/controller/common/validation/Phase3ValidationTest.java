package com.dcontrol.controller.common.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.dcontrol.controller.calls.CallsProtocol.PlaceCommand;
import com.dcontrol.controller.contacts.ContactsProtocol.ContactFields;
import com.dcontrol.controller.contacts.ContactsProtocol.Email;
import com.dcontrol.controller.contacts.ContactsProtocol.EmailType;
import com.dcontrol.controller.contacts.ContactsProtocol.Name;
import com.dcontrol.controller.contacts.ContactsProtocol.Organization;
import com.dcontrol.controller.contacts.ContactsProtocol.Phone;
import com.dcontrol.controller.contacts.ContactsProtocol.PhoneType;
import com.dcontrol.controller.sms.SmsProtocol.Participant;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class Phase3ValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private boolean dialable(String number) {
        return validator.validate(new PlaceCommand(UUID.randomUUID(), number, null)).isEmpty();
    }

    private static Name name(String given) {
        return new Name(given, null, null, null, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"+491512345678", "+49 (151) 234-5678", "0151.234.5678", "112", "00"})
    void dialNumbersAcceptDigitsWithSeparators(String number) {
        assertThat(dialable(number)).as(number).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"*#06#", "**21*0151234#", "#31#0151", "1", "+", "++4915", "4915+1", "+49 151 abc",
            "0151;123", "0151,123", "tel:0151", " ", ""})
    void dialNumbersRejectMmiCodesLettersAndPauses(String number) {
        assertThat(dialable(number)).as(number).isFalse();
    }

    @Test
    void dialNumbersAreLimitedToTwentyDigitsAndFortyCharacters() {
        assertThat(dialable("1".repeat(20))).isTrue();
        assertThat(dialable("1".repeat(21))).isFalse();
        assertThat(dialable("1" + " ".repeat(40) + "1")).isFalse();
        assertThat(dialable(null)).isFalse();
    }

    @Test
    void dialNumbersAreForwardedWithoutSeparators() {
        assertThat(DialNumber.Validator.normalize("+49 (151) 234-56.78")).isEqualTo("+491512345678");
        assertThat(new PlaceCommand(UUID.randomUUID(), "0151 234", 3).toDevice().toString()).contains("0151234");
    }

    @Test
    void storedNumbersAllowSenderNamesButNoControlCharacters() {
        assertThat(validator.validate(new Participant("VM-HDFCBK", null))).isEmpty();
        assertThat(validator.validate(new Participant("+49 151 2345678", "Alice"))).isEmpty();
        assertThat(validator.validate(new Participant("bad\nnumber", null))).isNotEmpty();
        assertThat(validator.validate(new Participant("x".repeat(65), null))).isNotEmpty();
        assertThat(validator.validate(new Participant("", null))).isNotEmpty();
    }

    @Test
    void contactFieldsNeedSomethingAndRespectTheCaps() {
        ContactFields valid = new ContactFields(name("Alice"), List.of(), List.of(), null, null, false);
        assertThat(validator.validate(valid)).isEmpty();

        ContactFields companyOnly = new ContactFields(name(null), List.of(), List.of(),
                new Organization("Example GmbH", null), null, false);
        assertThat(validator.validate(companyOnly)).isEmpty();

        ContactFields blank = new ContactFields(name("  "), List.of(), List.of(), new Organization(" ", "CTO"),
                "a note only", true);
        assertThat(validator.validate(blank)).isNotEmpty();

        ContactFields longNote = new ContactFields(name("Alice"), List.of(), List.of(), null, "x".repeat(2001), false);
        assertThat(validator.validate(longNote)).isNotEmpty();

        List<Phone> phones = java.util.stream.IntStream.range(0, 21)
                .mapToObj(i -> new Phone("+49 151 " + i, PhoneType.MOBILE, null)).toList();
        assertThat(validator.validate(new ContactFields(name("Alice"), phones, List.of(), null, null, false)))
                .isNotEmpty();

        Email longEmail = new Email("a".repeat(250) + "@x.de", EmailType.WORK, null);
        assertThat(validator.validate(new ContactFields(name("Alice"), List.of(), List.of(longEmail), null, null, false)))
                .isNotEmpty();
    }

    @Test
    void cursorsArePrintableAsciiOnly() {
        record Paged(@PageCursor String cursor) {
        }
        assertThat(validator.validate(new Paged("1698574530123|42"))).isEmpty();
        assertThat(validator.validate(new Paged(null))).isEmpty();
        assertThat(validator.validate(new Paged("x".repeat(257)))).isNotEmpty();
        assertThat(validator.validate(new Paged("café"))).isNotEmpty();
        assertThat(validator.validate(new Paged(""))).isNotEmpty();
    }
}
