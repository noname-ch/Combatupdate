package ch.bbcag.combatupdate;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;

// The field manual: a written book that says how the army works, handed to anyone who signs on
// their first soldier and to anyone who asks for one (/gipfaeliarmy manual, or the button on the
// menu).
//
// A vanilla written book rather than a screen of our own, because a book already pages, already
// opens with a right-click, already goes in a chest or a frame, and already works for somebody on
// the other end of a normal connection. All that is ours is what is written in it.
public final class GipfaeliManual {
    public static final String TITLE = "Gipfäli Army Field Manual";
    private static final String AUTHOR = "Gipfäli High Command";

    private GipfaeliManual() {
    }

    public static ItemStack book() {
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(
                Filterable.passThrough(TITLE), AUTHOR, 0, pages(), true));
        return book;
    }

    // Each page is written to fit a book page as it is drawn: about fourteen lines of nineteen
    // narrow characters, less for the wider ones. Long enough to say the thing, short enough that
    // nothing runs off the bottom unseen.
    private static List<Filterable<Component>> pages() {
        return List.of(
                page(title("GIPFÄLI ARMY"),
                        "\n\nField Manual\n\n",
                        "How to raise a squad, arm it, shape it and point it at things.\n\n",
                        "Keep this book. Sneak + right-click the flag opens the menu; everything in here is a button on it."),

                page(title("THE FLAG"),
                        "\n\nRight-click the air: raise a new squad — its commander appears.\n\n",
                        "Right-click a target: send the whole squad after it.\n\n",
                        "Sneak + right-click: open the army menu.\n\n",
                        "Right-click your own soldier: it holds here, or falls back in."),

                page(title("RECRUITING"),
                        "\n\nClick a commander: +1, +5, +10 or fill to 40. Soldiers cost rations and come unarmed, in the squad's colour.\n\n",
                        "Click a soldier (empty hand, or the flag) for its own menu: kit, armour, colour, posts.\n\n",
                        "Kit and armour come out of your pack. Creative pays for nothing."),

                page(title("KIT & ARMOUR"),
                        "\n\nThe kit in a soldier's hand IS its role. Hand it a different gun and it changes role on the spot.\n\n",
                        "Armour is vanilla armour: leather to netherite, and it protects like it does on you.\n\n",
                        "Or just right-click the soldier holding a gun, a helmet or a dye."),

                page(title("ROLES 1/2"),
                        "\n\n", bold("Rifleman"), " – Letony-Mate-AK47. Bursts, decent reach. The standard.\n\n",
                        bold("Assault"), " – Shotgun. Walks right in, takes a beating.\n\n",
                        bold("Marksman"), " – Long rifle. Hangs back, one hard hit."),

                page(title("ROLES 2/2"),
                        "\n\n", bold("Bomber"), " – Launcher. Homing Gipfäli. Won't fire point blank.\n\n",
                        bold("Panzer"), " – Heavy MG. Slow, armoured, endless fire.\n\n",
                        bold("Marcher"), " – The banner. No gun; makes everyone near it faster, harder and tougher."),

                page(title("AIM"),
                        "\n\nSoldiers lead their shots: they fire at where you will be, not where you are.\n\n",
                        "Nothing they fire hits their own side – you or the rest of the squad – so stand wherever you like.\n\n",
                        "(Both are config switches.)"),

                page(title("ORDERS"),
                        "\n\nFour, on the flag's menu for the army and on a commander for its squad:\n\n",
                        bold("Attack"), " – whatever you are looking at.\n",
                        bold("Stand"), " – on parade, here.\n",
                        bold("Hold"), " – stay put, shoot what comes.\n",
                        bold("Follow me"), " – fall in.\n\n",
                        "Or point at a target and click the flag."),

                page(title("TARGETS"),
                        "\n\nThe menu lists every player on the server, and every animal and monster around you, nearest first.\n\n",
                        "Click one. It glows for a moment so you can see where the squad is going.\n\n",
                        "A far target is marched on, step by step."),

                page(title("THE MAP"),
                        "\n\nOpen the territory map (M), pick a chunk, choose how many soldiers, and send them.\n\n",
                        "Free ground: they claim it for you when they arrive.\n\n",
                        "Somebody else's: they lay siege. As long as one of them stands in it, the capture runs as if you stood there."),

                page(title("GUARD POSTS"),
                        "\n\nCraft a Guard Post, set it down at whatever needs guarding, and click it. Posts are numbered as you place them.\n\n",
                        "A soldier's menu lists your posts: click Post 1 and it goes there and walks a beat.\n\n",
                        bold("Defend"), " monsters. ", bold("Aggressive"), " strangers too. ", bold("Passive"), " only when hit."),

                page(title("SQUADS"),
                        "\n\nA squad is a colour: up to 40 soldiers wearing it, plus one commander.\n\n",
                        "Paint a soldier blue and it is in the blue squad. Camouflage is the reserve.\n\n",
                        "Promote a soldier from its menu: it turns black with the squad's stripe. Click the commander for the whole squad's orders."),

                page(title("ATTACK & STAND"),
                        "\n\n", bold("Attack"), " – the squad in the field: it engages, follows orders, keeps its shape.\n\n",
                        bold("Stand"), " – on parade where you stand: ranks five wide and ten deep, at attention, shooting nothing unless shot at.\n\n",
                        bold("Stand follow"), " – the same ranks, marching after you."),

                page(title("FORMATIONS"),
                        "\n\n", bold("Loose"), " – bunch up behind you.\n",
                        bold("Line"), " – a rank abreast.\n",
                        bold("Wedge"), " – arrowhead, banner at the point.\n",
                        bold("Column"), " – single file, for tunnels.\n",
                        bold("Circle"), " – a ring around you.\n\n",
                        "A fight breaks formation; they re-form after."),

                page(title("COLOURS"),
                        "\n\nPick a colour on the menu and the whole squad wears it. Recruits match the squad.\n\n",
                        "Or right-click one soldier with a dye.\n\n",
                        "A red army and a blue army on one server is the whole point."),

                page(title("KEEPING THEM"),
                        "\n\nRight-click a soldier with a Gipfäli to heal it. They patch themselves up slowly when nothing is shooting.\n\n",
                        "A dead soldier drops its kit. Pick it up, hand it to the next one.\n\n",
                        "They teleport to you if left behind."),

                page(title("COMMANDS"),
                        "\n\n/gipfaeliarmy\n",
                        "  targets · status\n",
                        "  attack <name>\n",
                        "  follow · hold\n",
                        "  standdown · dismiss\n",
                        "  formation <shape>\n",
                        "  colour <dye>\n",
                        "  enlist <role>\n",
                        "  manual\n\n",
                        "Ops: recruit <role> [n]"));
    }

    private static Filterable<Component> page(Object... parts) {
        MutableComponent page = Component.empty();
        for (Object part : parts) {
            page.append(part instanceof Component component ? component : Component.literal(part.toString()));
        }

        return Filterable.passThrough(page);
    }

    private static Component title(String text) {
        return Component.literal(text).withStyle(ChatFormatting.BOLD, ChatFormatting.DARK_RED);
    }

    private static Component bold(String text) {
        return Component.literal(text).withStyle(ChatFormatting.BOLD);
    }
}
