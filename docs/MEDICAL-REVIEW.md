# Medical and safety evidence check

**Date:** 2026-11-04

**This was an evidence check against published guidelines. It was not a review by a licensed clinician. A licensed clinician (for example an emergency physician or wilderness medicine provider) should still read and sign off on the first-aid guides before the App Store release.**

## Scope

- All 69 bundled guides in `App/Resources/Guides/` (29 first aid, 14 survival, 13 water and sanitation, 13 radio and comms), plus `index.json` summaries and `checklists.json`.
- Every first-aid, water and sanitation, and survival instruction was compared with current published guidance. The radio guides were read for safety problems only (no medical content; frequencies were not re-verified in this pass).
- Three known concerns were settled first: snakebite limb position and pressure bandaging, aspirin for a suspected heart attack, and shock positioning.
- Text that was already correct was left alone. Changes keep each guide's style: short, plain, numbered steps.

## Sources used

| Short name | Source |
|---|---|
| AHA/ARC 2024 | 2024 American Heart Association and American Red Cross Guidelines for First Aid, *Circulation* 2024;150:e519–e579, doi:10.1161/CIR.0000000000001281. Read via the AHA "Highlights of the 2024 Guidelines for First Aid" (https://cpr.heart.org/-/media/CPR-Files/CPR-Guidelines-Files/2024-Highlights/Highlights-2024-FA-240927.pdf) and "2024 Guidelines for First Aid: Implications for Training Heartsaver Students" (https://cpr.heart.org/-/media/CPR2-Files/Tools-and-Resources/Training-Updates/2024-Training-Updates/2024Guidelines_on_FA_241106_FINAL.pdf). The full journal text was not reachable (publisher blocks automated access). |
| AHA 2025 | 2025 AHA Guidelines for CPR and ECC (adult and pediatric BLS, choking), as summarized by AAP/HealthyChildren (https://www.healthychildren.org/English/news/Pages/AHA-and-AAP-release-update-CPR-guidelines-to-help-save-young-lives.aspx) and Contemporary Pediatrics (https://www.contemporarypediatrics.com/view/aha-issues-2025-cpr-and-ecc-updates-for-choking-opioid-overdose-and-pediatric-care) |
| CDC NIOSH snakes | CDC/NIOSH, Venomous Snakes (https://www.cdc.gov/niosh/outdoor-workers/about/venomous-snakes.html) |
| EPA water | US EPA, Emergency Disinfection of Drinking Water (https://www.epa.gov/ground-water-and-drinking-water/emergency-disinfection-drinking-water) |
| CDC water supply | CDC, How to Create an Emergency Water Supply (https://www.cdc.gov/water-emergency/about/how-to-create-and-store-an-emergency-water-supply.html) |
| CDC bleach | CDC, Cleaning and Disinfecting with Bleach (https://www.cdc.gov/hygiene/about/cleaning-and-disinfecting-with-bleach.html) |
| CSACI | Canadian Society of Allergy and Clinical Immunology, Anaphylaxis in Schools and Other Settings, 3rd ed. (https://csaci.ca/EN/files/basic-html/page15.html) |

## How the three known concerns were settled

1. **Snakebite.** AHA/ARC 2024 (written for US and Canadian snakes): rest and immobilize the limb, remove rings, call EMS; ice is of unproven benefit; suction, electric shock, tourniquets and **pressure-immobilization bandaging are potentially harmful**. AHA/ARC 2024 gives no limb-height recommendation. CDC/NIOSH says to keep the bite "in a neutral position of comfort." Older Red Cross and Army material says "below the heart." The guide keeps "about the level of the heart" and now adds "don't raise it high or let it hang down," plus a Do NOT line for tight elastic or pressure wraps. No tourniquet, cutting, suction or ice was already in the guide.
2. **Aspirin.** AHA/ARC 2024: alert adults with chest pain not caused by injury may chew and swallow 162–325 mg, unless they have a known allergy or a health professional has told them not to; if in doubt, wait for EMS. The guide now says 1 x 325 mg or 2 to 4 x 81 mg, adds "the dispatcher or their own doctor hasn't told them otherwise" and "if you are not sure, skip it and wait for EMS." The existing "not for children or teens, not for stroke" notes were already correct.
3. **Shock.** AHA/ARC 2024: lie the person flat on their back (supine). Raising the feet is a weaker option, only when there is no sign of injury, and should not be done if it causes pain. The guide already had "lie flat" as the main step. The leg-raise tip is reworded to match: no-injury cases only, 6 to 12 in, not with a possible head, neck, back, hip, pelvis or leg injury, not if it hurts.

## Changes

| Guide | Old wording (summary) | New wording (summary) | Source |
|---|---|---|---|
| first-aid/heart-attack | Chew 325 mg or 2–4 x 81 mg if not allergic, no bleeding problems, not told by a doctor to avoid it | Same doses, for an alert adult; adds "the dispatcher or their own doctor hasn't told them otherwise" and "if not sure, skip it and wait for EMS" | AHA/ARC 2024 |
| first-aid/shock | "Some courses teach raising legs 12 in... only if trained" | Lying flat is the main step; may raise feet 6–12 in only with no sign of injury; not with a possible head/neck/back/hip/pelvis/leg injury or if it causes pain; lower them if breathing gets harder | AHA/ARC 2024 |
| first-aid/snakebite | Limb still, about heart level | Adds "don't raise it high or let it hang down" | CDC NIOSH snakes; AHA/ARC 2024 |
| first-aid/snakebite | Do NOT list had no mention of pressure wraps | Adds "Don't wrap it tightly with an elastic or pressure bandage" (Australian pressure wraps are not for US snakes) | AHA/ARC 2024 |
| first-aid/choking | "AHA teaches abdominal thrusts alone, Red Cross teaches back blows plus thrusts" | Since 2025 the AHA also teaches 5 back blows then 5 abdominal thrusts (the guide's existing steps) | AHA 2025 |
| first-aid/choking | Infant chest thrusts with 2 fingers | Heel of one hand | AHA 2025 |
| first-aid/cpr | Infant compressions: 2 fingers or 2 thumbs encircling | Heel of 1 hand or 2 thumbs encircling (2-finger method removed) | AHA 2025 |
| first-aid/bleeding | Improvised tourniquet at least 1.5 in (4 cm) wide | At least 2 in (5 cm) wide | AHA/ARC 2024 |
| first-aid/bleeding | Sucking chest wound: vented chest seal or a gloved hand | Vented chest seal; otherwise leave open or cover loosely with dry gauze/cloth; don't seal airtight; loosen if breathing worsens | AHA/ARC 2024 |
| first-aid/hypothermia | Warm packs "or skin-to-skin contact" | Skin-to-skin only if nothing else is available | AHA/ARC 2024 |
| first-aid/hypothermia | "Don't put a severely cold person in a hot bath" | Don't use a warm bath or shower for someone confused, very sleepy, or not shivering (blood pressure drop) | AHA/ARC 2024 |
| first-aid/nosebleeds | Pinch again; get care if it lasts more than 20 minutes; cold pack may help | Get care if still bleeding after 15 minutes of firm pressure, or if very heavy, faint, or after a head injury; cold-pack line removed (benefit unknown) | AHA/ARC 2024 |
| first-aid/seizures | Call 911 if they don't wake up after the seizure | Call 911 if not back to normal 5 to 10 minutes after it stops; adds a seizure in a baby under 6 months | AHA/ARC 2024 |
| water-sanitation/water-storage | Untreated or well water: add 2 drops of bleach per gallon before storing | Disinfect it first: 8 drops of 6% or 6 drops of 8.25% bleach per gallon, wait 30 minutes | EPA water; CDC water supply |
| water-sanitation/disinfecting-surfaces | Household bleach "about 5 to 8% strength" | "5 to 9% sodium hypochlorite" | CDC bleach |

No change was needed to `index.json` summaries or `checklists.json`; they still match the guides. The unit tests only check that each guide file exists.

## Checked and left as is (already matches current guidance)

CPR rate (100–120/min) and adult depth (2–2.4 in); hands-only CPR; AED child pads; anaphylaxis (epinephrine first, second dose after 5–15 minutes if no improvement, CSACI); burns (cool running water 20 minutes, no ice or butter, remove jewelry, petroleum jelly or aloe on small burns, when to seek care); chemical skin and eye flushing (15–20 minutes); frostbite (37–40 °C / 99–104 °F water, no refreezing, no rubbing, loose bulky dressings; AHA/ARC 2024 uses the same range); heat stroke (cold-water immersion to the neck, cool first); jellyfish (remove tentacles, seawater rinse, hot water for pain); bee stings, ticks, spiders, scorpions; tourniquet placement 2–3 in above the wound, not on a joint, never loosen; wound packing times; poisoning (don't induce vomiting, Poison Control 1-800-222-1222, naloxone); asthma (inhaler with spacer); hypoglycemia (15–20 g fast sugar, glucose tablets first); stroke (BE FAST, no aspirin); sprains (cold 20 minutes, compression); fractures (splint as found); concussion; ORS recipe (WHO: 6 level tsp sugar, 1/2 level tsp salt per liter); EPA bleach table for 6% and 8.25% (all rows match), EPA iodine (5 drops per quart, 10 if cloudy, 30 minutes); boiling 1 minute (3 minutes above 6,500 ft, CDC); container sanitizing (1 tsp bleach per quart, 30 seconds); surface bleach mix (5 tbsp per gallon or 4 tsp per quart, 1 minute); food safety without power and flood-can sanitizing; generator 20 ft; survival and radio guides (no unsafe instructions found).

## Unsure, or needs a clinician's judgement

1. **Snakebite limb height.** No current primary source gives a firm height. AHA/ARC 2024 is silent, CDC/NIOSH says "neutral position of comfort," older material says "below the heart," and the 2026 Wilderness Medical Society pit viper update (doi:10.1177/10806032261451823) could not be read. "About heart level" is a middle position. A clinician should confirm it.
2. **Coral snakes and bites outside North America.** The guide is written for US snakes. Pressure-immobilization is standard in Australia and is suggested by WHO for some elapid bites elsewhere. The app does not cover non-US snakes.
3. **Shock leg raise.** The AHA/ARC 2024 full text could not be read; the wording comes from AHA summaries. Sources differ on the height (6–12 in vs 30–60°).
4. **Boiling at altitude.** CDC says 3 minutes above 6,500 ft (2,000 m); EPA says above 5,000 ft. The guide keeps CDC's number. Using EPA's lower threshold would be more cautious.
5. **Bleach for cloudy water.** EPA says to double the dose for cloudy, colored or very cold water (the guide follows this). Some CDC sheets say to filter first and then use the double dose. The guide already says to settle and filter first.
6. **Hypoglycemia timing.** The guide uses the ADA "15-15" rule (wait 15 minutes). AHA/ARC 2024 says to call EMS if no improvement within 10 minutes. These are close but not the same.
7. **Spinal injury.** The guide teaches holding the head still. Newer guidance moves away from routine immobilization (AHA/ARC 2024 recommends against it for penetrating trauma). A clinician should decide whether lay manual stabilization should stay.
8. **Anaphylaxis position** (lie on back with legs raised) follows common allergy-society advice. The AHA summaries read for this check did not cover it.
9. **Hypothermia caffeine** ("no alcohol or caffeine") and the "no one is dead until warm and dead" saying are traditional teaching and were not checked against a primary source.
10. **Radio frequencies and rules** were not re-verified in this pass.
