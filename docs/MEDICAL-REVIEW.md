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
| AHA/ARC 2024 | 2024 American Heart Association and American Red Cross Guidelines for First Aid, *Circulation* 2024;150:e519–e579, doi:10.1161/CIR.0000000000001281. Read via the AHA "Highlights of the 2024 Guidelines for First Aid" (https://cpr.heart.org/-/media/CPR-Files/CPR-Guidelines-Files/2024-Highlights/Highlights-2024-FA-240927.pdf) and "2024 Guidelines for First Aid: Implications for Training Heartsaver Students" (https://cpr.heart.org/-/media/CPR2-Files/Tools-and-Resources/Training-Updates/2024-Training-Updates/2024Guidelines_on_FA_241106_FINAL.pdf). The first pass used only these summaries; the second pass (below) read the full text and its recommendation tables at https://www.ahajournals.org/doi/full/10.1161/CIR.0000000000001281. |
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

CPR rate (100–120/min) and adult depth (2–2.4 in); hands-only CPR; AED child pads; anaphylaxis (epinephrine first, CSACI; positioning and second-dose timing changed in the second pass); burns (cool running water 20 minutes, no ice or butter, remove jewelry, petroleum jelly or aloe on small burns, when to seek care); chemical skin and eye flushing (15–20 minutes); frostbite (37–40 °C / 99–104 °F water, no refreezing, no rubbing, loose bulky dressings; AHA/ARC 2024 uses the same range); heat stroke (cold-water immersion to the neck, cool first); jellyfish (remove tentacles, seawater rinse, hot water for pain); bee stings, ticks, spiders, scorpions; tourniquet placement 2–3 in above the wound, not on a joint, never loosen; wound packing times; poisoning (don't induce vomiting, Poison Control 1-800-222-1222, naloxone); asthma (inhaler with spacer); hypoglycemia (glucose tablets first; amount and timing changed in the second pass); stroke (BE FAST, no aspirin); sprains (cold 20 minutes, compression); fractures (splint as found); concussion; ORS recipe (WHO: 6 level tsp sugar, 1/2 level tsp salt per liter); EPA bleach table for 6% and 8.25% (all rows match), EPA iodine (5 drops per quart, 10 if cloudy, 30 minutes); boiling 1 minute (altitude threshold changed in the second pass); container sanitizing (1 tsp bleach per quart, 30 seconds); surface bleach mix (5 tbsp per gallon or 4 tsp per quart, 1 minute); food safety without power and flood-can sanitizing; generator 20 ft; survival and radio guides (no unsafe instructions found).

## Second pass (2026-11-04): open questions checked against primary sources

The first pass left ten questions open. This pass read the primary texts where they were free.

### More sources

| Short name | Source |
|---|---|
| ERC 2025 | European Resuscitation Council Guidelines 2025: First Aid, *Resuscitation* 2025, doi:10.1016/j.resuscitation.2025.110752 (based on the ILCOR 2025 first aid CoSTR) |
| ILCOR SMR | ILCOR 2024, Spinal motion restriction (FA 7311, TF ScR), https://costr.ilcor.org/document/spinal-motion-restriction-fa-7311-tf-scr |
| WAO 2020 | World Allergy Organization Anaphylaxis Guidance 2020, *World Allergy Organ J*, PMC7607509 |
| WMS hypothermia 2019 | Dow et al., Wilderness Medical Society Clinical Practice Guidelines for the Out-of-Hospital Evaluation and Treatment of Accidental Hypothermia: 2019 Update, *Wilderness Environ Med* 2019 |
| Lavonas 2011 | Lavonas et al., Unified treatment algorithm for the management of crotaline snakebite in the United States, *BMC Emerg Med* 2011, https://pmc.ncbi.nlm.nih.gov/articles/PMC3042971/ |
| Parker-Cote 2018 | Parker-Cote and Meggs, First Aid and Pre-Hospital Management of Venomous Snakebites, *Trop Med Infect Dis* 2018, https://pmc.ncbi.nlm.nih.gov/articles/PMC6073535/ |
| ANZCOR 9.4.8 | Australian and New Zealand Committee on Resuscitation, Guideline 9.4.8 Pressure Immobilisation (read in a third-party copy) |
| NOLS | NOLS Wilderness Medicine articles and case studies, nols.edu (snakebite, hypothermia, spine assessment) |
| CDC boil | CDC, Emergency water (https://www.cdc.gov/water-emergency/about/index.html) |
| WHO boil | WHO, Boil water technical brief WHO/FWC/WSH/15.02 (2015) |
| eCFR 47 | Electronic Code of Federal Regulations, Title 47 Parts 80, 95, 97 (ecfr.gov, issue date 2026-11-02); ARRL band plan; USCG MSIB 07-24; NWS NOAA Weather Radio; meshtastic.org LoRa configuration |

### Results

| # | Question | Finding | Change |
|---|---|---|---|
| 1 | Snakebite limb height | No source reached recommends raising the limb in the field or letting it hang. Lavonas 2011 did not cover field first aid (elevation there is a hospital step). NOLS: splint, avoid elevating. Parker-Cote 2018: below-heart may increase local damage from pit viper venom. The 2026 WMS update is subscription only; its abstract says nothing on position. | Kept "about heart level"; added "relaxed, slightly bent" and "a loose splint can help." **Still worth a clinician's look**, since the newest WMS text was not read. |
| 2 | Coral snakes and non-US snakes | Pressure wraps are not recommended for pit vipers (Lavonas 2011, AHA/ARC 2024). ANZCOR recommends them for all Australian snakes. NOLS (2011) and a Red Cross snippet allow a trained rescuer to wrap a US coral snake bite when the hospital is far; the evidence is from animal studies only. | Do NOT line now names the pit vipers. Added a scope note: US snakes only; outside North America follow local advice; in Australia a pressure bandage is standard. No coral snake wrap advice for laypeople. **Clinician question:** should trained users get a coral snake wrap note? |
| 3 | Shock leg raise | AHA/ARC 2024 full text: lie flat if alert; recovery position if vomiting, less alert, or left alone; leg raise "about 6 to 12 in (about 30 to 45 degrees)" may be reasonable only with no injury; return to flat if it causes pain or worse symptoms. ERC 2025: optimal angle unknown (studies 30 to 60 degrees), not routine. | Guide already matched. Added "or you must leave them" to the side position, and "if they feel worse" to lowering the legs. **Settled.** |
| 4 | Boiling at altitude | CDC: 3 minutes above 6,500 ft. EPA: 3 minutes above 5,000 ft (its "1,000 meters" conversion is wrong; 5,000 ft is about 1,500 m). WHO: a rolling boil is enough at any altitude. | Changed to the more cautious EPA threshold, 5,000 ft (about 1,500 m), in making-water-safe, menstrual-infant-hygiene and index.json. **Settled.** |
| 5 | Bleach for cloudy water | CDC and EPA both say filter or settle first, then double the dose for cloudy, colored or very cold water. | Reworded the doubling line to say filter first. **Settled.** |
| 6 | Low blood sugar timing | AHA/ARC 2024: at least 20 g oral glucose, tablets preferred; call EMS if not better within 10 minutes. Its table lists 6 to 8 oz of juice for 15 g or more. ERC 2025 and ADA repeat at 15 minutes. | Changed to about 20 g, 6 to 8 oz (180 to 240 mL) juice or soda, call 911 at 10 minutes and repeat the sugar while waiting. The old 4 oz was too little. **Settled** (follows AHA, the US first aid standard). |
| 7 | Spinal injury | AHA/ARC 2024: have the person stay as still as possible and call EMS; against collars and long boards by first aid providers; against routine immobilization for penetrating trauma; no advice on holding the head. ILCOR 2024: against collars; too little evidence on manual stabilization. ERC 2025: let an awake person hold their own head; never force a position; manual head hold only for an unresponsive person on their back. NOLS: the spine assessment that lets a rescuer stop holding the head is taught at WAFA/WFR level, not to the public. | Rewrote the steps: awake person keeps their own position; hold the head only if they are not awake and on their back; no collar or board; for stab or gunshot wounds, control bleeding first. Summary in index.json updated. **Settled** for lay first aid. |
| 8 | Anaphylaxis position | AHA/ARC 2024: no positioning advice; second dose if EMS is more than 5 to 10 minutes away. ERC 2025: keep them seated or lying; second dose after 5 minutes. WAO 2020: lie on the back unless breathing is hard (then sit); recovery position if unconscious; raising the legs is "controversial." No source read supports "standing up suddenly can make blood pressure crash" (it may come from EAACI or Resuscitation Council UK, not checked). | Dropped "legs raised" and the unsupported blood-pressure line. Now: lie on back, sit up if breathing is hard, side if vomiting or passed out; keep them sitting or lying; second dose after 5 to 10 minutes if help isn't there. **Settled.** |
| 9 | Hypothermia drinks and "warm and dead" | WMS 2019: an alert, shivering person should get high-carbohydrate drinks or food, warm but not hot; caffeine is not mentioned. NOLS suggests hot cocoa. WMS 2019 on "warm and dead": death is hard to judge in the cold, but don't start CPR if the chest is too stiff to compress or injuries are clearly fatal; check the pulse for 1 minute; delayed or interrupted CPR is acceptable if continuous CPR is unsafe. | Drinks line now: sweet drink or food if awake and shivering, warm not hot, no alcohol. Dropped "caffeine." Replaced the saying with WMS's practical steps. **Settled.** |
| 10 | Radio frequencies and rules | Checked every radio guide against eCFR Title 47, the ARRL band plan, USCG and NWS. | Fixed: GMRS allows business talk (no ads); ham emergency rules (97.403, 97.405) cover licensed stations only, with a GMRS licensee allowed to lend a radio for an emergency; "Mayday" is not allowed on FRS/GMRS except for vessels or vehicles in immediate danger; marine 22A may show as 1022; 6 m, 1.25 m and 10 m frequencies are common simplex, not "national calling"; Meshtastic licensed mode needs your call sign (the "more power" claim was removed, not verified). Not verified from an official source: 144.390 APRS, the 14.300 and 14.325 nets, the NOAA "6-digit" SAME code. **Settled** apart from those. |

### Still open

- The 2026 Wilderness Medical Society pit viper update (snakebite limb position) was not read in full.
- Whether trained users should get coral snake pressure-wrap advice.
- The Red Cross coral snake text and AHA 2024 snakebite table were not compared word for word.
- A licensed clinician has still not read the guides. With the second pass, a short review of the snakebite questions above is what remains.
