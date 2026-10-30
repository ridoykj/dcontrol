package com.dcontrol.client.feature.contacts.data

import com.dcontrol.client.core.websocket.CommandException
import com.dcontrol.client.core.websocket.protocol.ContactFields
import com.dcontrol.client.core.websocket.protocol.EmailTypes
import com.dcontrol.client.core.websocket.protocol.ErrorCodes
import com.dcontrol.client.core.websocket.protocol.Phase3Limits
import com.dcontrol.client.core.websocket.protocol.PhoneTypes

/** Re-checks `ContactFields` on the phone (the controller validated them too) and trims blank values. */
object ContactFieldsValidator {

    fun validate(fields: ContactFields): ContactFields {
        val name = fields.name.copy(
            display = "",
            given = text(fields.name.given, Phase3Limits.MAX_NAME, "name.given"),
            middle = text(fields.name.middle, Phase3Limits.MAX_NAME, "name.middle"),
            family = text(fields.name.family, Phase3Limits.MAX_NAME, "name.family"),
            prefix = text(fields.name.prefix, Phase3Limits.MAX_NAME, "name.prefix"),
            suffix = text(fields.name.suffix, Phase3Limits.MAX_NAME, "name.suffix"),
        )
        if (fields.phones.size > Phase3Limits.MAX_CONTACT_ITEMS) invalid("At most ${Phase3Limits.MAX_CONTACT_ITEMS} phones")
        if (fields.emails.size > Phase3Limits.MAX_CONTACT_ITEMS) invalid("At most ${Phase3Limits.MAX_CONTACT_ITEMS} emails")
        val phones = fields.phones.map { phone ->
            val number = phone.number.trim()
            if (number.isEmpty() || number.length > Phase3Limits.MAX_PHONE_NUMBER || number.any { it.isISOControl() }) {
                invalid("Invalid phone number")
            }
            if (phone.type !in PhoneTypes.ALL) invalid("Invalid phone type")
            phone.copy(number = number, label = label(phone.label, phone.type == PhoneTypes.CUSTOM))
        }
        val emails = fields.emails.map { email ->
            val address = email.address.trim()
            if (address.isEmpty() || address.length > Phase3Limits.MAX_EMAIL || address.any { it.isISOControl() }) {
                invalid("Invalid email address")
            }
            if (email.type !in EmailTypes.ALL) invalid("Invalid email type")
            email.copy(address = address, label = label(email.label, email.type == EmailTypes.CUSTOM))
        }
        val company = text(fields.organization?.company, Phase3Limits.MAX_NAME, "organization.company")
        val title = text(fields.organization?.title, Phase3Limits.MAX_NAME, "organization.title")
        val organization = if (company == null && title == null) null else fields.organization?.copy(company = company, title = title)
        val note = fields.note?.takeIf { it.isNotBlank() }?.also {
            if (it.length > Phase3Limits.MAX_NOTE) invalid("note is too long")
        }
        val hasName = listOf(name.given, name.middle, name.family, name.prefix, name.suffix).any { it != null }
        if (!hasName && phones.isEmpty() && emails.isEmpty() && company == null) {
            invalid("A contact needs a name, phone, email or company")
        }
        return ContactFields(name, phones, emails, organization, note, fields.starred)
    }

    private fun text(value: String?, max: Int, field: String): String? {
        val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (trimmed.length > max || trimmed.any { it.isISOControl() }) invalid("Invalid $field")
        return trimmed
    }

    private fun label(value: String?, custom: Boolean): String? {
        if (!custom) return null
        return text(value, Phase3Limits.MAX_LABEL, "label")
    }

    private fun invalid(message: String): Nothing = throw CommandException(ErrorCodes.VALIDATION, message)
}
