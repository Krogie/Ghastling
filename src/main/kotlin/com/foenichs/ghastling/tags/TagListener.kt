package com.foenichs.ghastling.tags

import dev.minn.jda.ktx.events.listener
import dev.minn.jda.ktx.events.onCommand
import dev.minn.jda.ktx.events.onCommandAutocomplete
import dev.minn.jda.ktx.interactions.components.Modal
import dev.minn.jda.ktx.interactions.components.StringSelectMenu
import dev.minn.jda.ktx.interactions.components.option
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.components.container.Container
import net.dv8tion.jda.api.components.textdisplay.TextDisplay
import net.dv8tion.jda.api.components.textinput.TextInput
import net.dv8tion.jda.api.components.textinput.TextInputStyle
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.exceptions.ErrorHandler
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback
import net.dv8tion.jda.api.requests.ErrorResponse
import org.slf4j.LoggerFactory

object TagListener {

    private val logger = LoggerFactory.getLogger(TagListener::class.java)

    /**
     * Registers all necessary event listeners for tag functionality.
     */
    fun register(jda: JDA) {
        jda.listener<MessageReceivedEvent> { onPrefix(it) }
        jda.onCommandAutocomplete("tags", "name") { onAutocomplete(it) }
        jda.onCommand("tags") { onSlashCommand(it as SlashCommandInteractionEvent) }
        jda.listener<ModalInteractionEvent> { onModal(it) }
    }

    /**
     * Processes prefix-based tag triggers and handles cooldowns or permissions.
     */
    private fun onPrefix(event: MessageReceivedEvent) {
        if (event.author.isBot || !event.isFromGuild) return
        val msg = event.message.contentRaw
        if (!msg.startsWith("!t ")) return

        val keyword = msg.removePrefix("!t ").trim()
        if (keyword.isBlank()) return

        val guildId = event.guild.idLong
        val tag = TagService.find(guildId, keyword) ?: return

        val cooldown = TagService.checkCooldown(event.channel.idLong, tag.primary)
        if (cooldown > 0) {
            deleteSilently(event.message, guildId)
            return
        }

        deleteSilently(event.message, guildId)

        try {
            val container = tag.cachedContainer
            val action = if (container != null) {
                event.channel.sendMessageComponents(container).useComponentsV2()
            } else {
                event.channel.sendMessage(tag.content)
            }

            action.queue({
                TagService.incrementUsage(guildId, tag.primary)
            }, { error ->
                logger.warn("Failed to send tag in Guild $guildId: ${error.message}")
            })
        } catch (e: InsufficientPermissionException) {
            logger.warn("Missing permissions in Guild $guildId: ${e.permission}")
        } catch (e: Exception) {
            logger.error("Error sending tag in Guild $guildId", e)
        }
    }

    /**
     * Supplies autocomplete choices for tag names based on user input.
     */
    private fun onAutocomplete(event: CommandAutoCompleteInteractionEvent) {
        if (event.subcommandName != "manage") return
        val query = event.focusedOption.value
        event.replyChoices(TagService.autocomplete(event.guild!!.idLong, query)).queue(null) { error ->
            logger.warn("Autocomplete failed for Guild {}: {}", event.guild!!.id, error.message)
        }
    }

    private fun onSlashCommand(event: SlashCommandInteractionEvent) {
        when (event.subcommandName) {
            "show" -> onShowTags(event)
            "manage" -> onManageTag(event)
        }
    }

    private fun onShowTags(event: SlashCommandInteractionEvent) {
        val tags = TagService.listTags(event.guild!!.idLong)
            .sortedWith(compareByDescending<Tag> { it.usages }.thenBy { it.primary })

        if (tags.isEmpty()) {
            event.replyContainer("This server doesn't have any tags yet.")
            return
        }

        event.replyContainer(formatTagList(event.guild!!.name, tags))
    }

    private fun formatTagList(guildName: String, tags: List<Tag>): String {
        val uncategorized = mutableListOf<String>()
        val categories = mutableMapOf<String, MutableList<String>>()

        for (tag in tags) {
            val parts = tag.primary.split(" ", limit = 2)
            if (parts.size > 1) {
                categories.getOrPut(parts[0]) { mutableListOf() }.add(parts[1])
            } else {
                uncategorized.add(tag.primary)
            }
        }

        val sb = StringBuilder()
        sb.append("### Tags of $guildName (${tags.size})\n")

        if (uncategorized.isNotEmpty()) {
            sb.append(uncategorized.joinToString(", ") { "`$it`" })
        }

        for ((category, items) in categories.toSortedMap()) {
            val entry = "\n\n**$category** (${items.size}):\n" +
                    items.joinToString(", ") { "`$it`" }

            if (sb.length + entry.length > 3950) {
                sb.append("\n\n...and more.")
                break
            }
            sb.append(entry)
        }

        return sb.toString()
    }

    private fun onManageTag(event: SlashCommandInteractionEvent) {
        val guildId = event.guild!!.idLong
        val name = event.getOption("name")?.asString ?: return
        val delete = event.getOption("remove")?.asBoolean ?: false
        val existing = TagService.find(guildId, name)

        if (delete) {
            if (existing != null) {
                TagService.delete(guildId, existing.primary)
                event.replyContainer("Successfully removed the `${existing.primary}` tag.")
            } else {
                event.replyContainer("Couldn't find this tag, please try again.")
            }
            return
        }

        if (existing == null) {
            val conflicts = TagService.findConflicts(guildId, name)
            if (conflicts.isNotEmpty()) {
                event.replyContainer(formatConflicts(conflicts))
                return
            }
        }

        event.replyModal(buildTagModal(existing, name)).queue(null) { error ->
            logger.warn("Failed to open tag modal in Guild {}: {}", guildId, error.message)
        }
    }

    /**
     * Processes modal submissions to save or update tags.
     */
    private fun onModal(event: ModalInteractionEvent) {
        if (!event.modalId.startsWith("tag_modal:")) return
        val guildId = event.guild!!.idLong
        val oldPrimary = event.modalId.removePrefix("tag_modal:").ifEmpty { null }

        val keywords = event.getValue("keywords")?.asString ?: return
        val content = event.getValue("content")?.asString ?: return
        val styleStr = event.getValue("style")?.asStringList?.firstOrNull() ?: return
        val style = runCatching { TagStyle.valueOf(styleStr) }.getOrDefault(TagStyle.Accent)

        val conflicts = TagService.findConflicts(guildId, keywords, ignorePrimary = oldPrimary)
        if (conflicts.isNotEmpty()) {
            event.replyContainer(formatConflicts(conflicts))
            return
        }

        try {
            val newPrimary = TagService.createOrUpdate(guildId, keywords, content, style, oldPrimary)
            event.replyContainer("Successfully saved the `$newPrimary` tag.")
            logger.info("Guild $guildId: Tag '$newPrimary' updated by ${event.user.name}")
        } catch (e: IllegalArgumentException) {
            event.replyContainer("Error: ${e.message}")
        } catch (e: Exception) {
            logger.error("Modal Error", e)
            event.replyContainer("An unexpected error occurred.")
        }
    }

    /**
     * Helper to send an ephemeral styled response.
     */
    private fun IReplyCallback.replyContainer(markdown: String) {
        val container = Container.of(TextDisplay.of(markdown)).withAccentColor(TagService.accentColor)
        this.replyComponents(container).useComponentsV2().setEphemeral(true).queue(null) { error ->
            logger.warn("Failed to send ephemeral reply: {}", error.message)
        }
    }

    private fun deleteSilently(message: Message, guildId: Long) {
        try {
            message.delete().queue(
                null, ErrorHandler().ignore(ErrorResponse.UNKNOWN_MESSAGE, ErrorResponse.MISSING_PERMISSIONS)
            )
        } catch (e: InsufficientPermissionException) {
            logger.debug("Cannot delete message in Guild {}: {}", guildId, e.permission)
        }
    }

    private fun buildTagModal(existing: Tag?, name: String) =
        Modal("tag_modal:${existing?.primary ?: ""}", if (existing != null) "Edit Tag: ${existing.primary}" else "Create Tag") {
            val kw = TextInput.create("keywords", TextInputStyle.SHORT).setPlaceholder("nolfg, m, lfg")
                .setValue(existing?.keywords ?: name).setRequired(true).build()
            label(
                "Keywords",
                child = kw,
                description = "These will trigger the tag, with the first one being the primary one. Separated by commas."
            )

            val content = TextInput.create("content", TextInputStyle.PARAGRAPH)
                .setPlaceholder("### Multiplayer requests are not allowed here!\nPlease use the LFG category for multiplayer...")
                .setValue(existing?.content).setRequired(true).build()
            label(
                "Content",
                child = content,
                description = "The actual content of the tag, fully supporting markdown."
            )

            val defaultStyle = existing?.style ?: TagStyle.Accent
            val style = StringSelectMenu("style", placeholder = "Select a style") {
                option(
                    "Ghastling Embed",
                    value = "Accent",
                    default = defaultStyle == TagStyle.Accent,
                    description = "An embed with Ghastling's accent color."
                )
                option(
                    "No Accent",
                    "NoAccent",
                    default = defaultStyle == TagStyle.NoAccent,
                    description = "An embed without an accent color."
                )
                option(
                    "Raw Message",
                    "Raw",
                    default = defaultStyle == TagStyle.Raw,
                    description = "No embed, just the raw tag content."
                )
            }
            label("Style", child = style, description = "Choose how the tag content will be displayed.")
        }

    private fun formatConflicts(conflicts: Map<String, String>): String {
        return "### Conflicting keywords\n" + conflicts.entries.joinToString("\n") { "- `${it.key}` -> `${it.value}`" }
    }
}