package me.lovkar.errands.mixin;

import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.packets.MicrophonePacket;
import me.lovkar.errands.AudioGate;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Real barge-in for pregenerated clips: the moment the player starts speaking,
 * any cached greeting/delivery clip playing near them is cut off, so the
 * citizen doesn't finish a canned speech over the player's words. (Live
 * sessions handle this via Gemini's own interrupted signal; pregen clips have
 * no session, so this is the only ear they get.)
 *
 * Uses the same voice-activity heuristic as mc_talking itself: an Opus packet
 * longer than 10 bytes counts as speech.
 */
@Mixin(targets = "me.sshcrack.mc_talking.McTalkingVoicechatPlugin", remap = false)
public abstract class McTalkingVoicechatPluginMixin {

    @Inject(method = "handleMicPacket", at = @At("HEAD"), remap = false)
    private void colonist_errands$bargeInCutsPregen(MicrophonePacketEvent event, CallbackInfo ci) {
        try {
            if (!AudioGate.hasActivePregen()) {
                return;
            }
            VoicechatConnection sender = event.getSenderConnection();
            if (sender == null) {
                return;
            }
            MicrophonePacket packet = (MicrophonePacket) event.getPacket();
            byte[] opus = packet.getOpusEncodedData();
            if (opus == null || opus.length <= 10) {
                return; // silence / DTX frame
            }
            Object p = sender.getPlayer().getPlayer();
            if (p instanceof ServerPlayer sp) {
                // Debounced inside: only sustained speech cuts clips, not a cough.
                AudioGate.onPlayerVoicePacket(sp, opus.length);
            }
        } catch (Throwable ignored) {
        }
    }
}
