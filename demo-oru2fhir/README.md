# Démo oru2fhir : un ORU^R01 devient un Bundle FHIR 

Un compte rendu de laboratoire **HL7 v2 `ORU^R01`** entre dans Open Integration Engine. Il en sort un
**Bundle FHIR R4 `transaction`** (Patient, Encounter, ServiceRequest, Specimen, Observation,
DiagnosticReport), validé contre une IG d'exemple. La transformation tient en sept étapes
**FHIR Profile Mapper**, renseignées dans l'éditeur graphique : ni FML, ni script écrit à la main.

La démo est autonome : OIE 4.7.0, sa base PostgreSQL, l'administration web, et le plugin de ce dépôt
compilé depuis ses sources. Rien d'autre à cloner ni à lancer.

## Prérequis

- **Docker**, avec Compose 2.21 ou plus récent (`additional_contexts`).
- **Accès réseau au premier build**, pour :
  - les sources d'OIE 4.7.0 sur GitHub ;
  - les dépendances Maven du plugin ;
  - la release du plugin Web Support.
- **Environ 3 Go de mémoire libre** : OIE démarre avec 2 Go de heap.
- **Ports libres** : 8443 (administration) et 8081 (le canal). Pour les changer, copier
  `.env.example` en `.env`.

## Lancer

```bash
docker compose up -d --build
docker compose logs -f oie-init        # « === Terminé : 1 canal(aux) importé(s) et déployé(s) === »
```

**Le premier build est long.** OIE est compilé depuis ses sources avec Gradle, puis le plugin avec
Maven : compter plusieurs minutes. Les builds suivants reprennent le cache.

Au démarrage, `oie-init` importe et déploie le canal d'`oie-init/channels/`, une seule fois. Un canal
modifié dans l'administration n'est pas écrasé au démarrage suivant.

## Envoyer un message

```bash
./examples/send.sh                                            # l'exemple complet : renvoie le Bundle
./examples/send.sh examples/oru-r01-minimal.er7               # un seul résultat
./examples/send.sh examples/oru-r01-sans-date-de-naissance.er7   # refusé : réponse vide
```

Ce script fait ceci :

```bash
curl -X POST http://localhost:8081/oru/ -H 'Content-Type: text/plain; charset=UTF-8' \
     --data-binary @examples/oru-r01-labo.er7
```

Le `charset=UTF-8` compte : sans lui, l'écouteur HTTP lit le message en ISO-8859-1 et abîme les
accents.

[`examples/demo.http`](examples/demo.http) contient les mêmes envois, plus quelques appels à l'API
d'OIE et du plugin (profils, snapshot, ConceptMaps, `$translate` local), prêts à exécuter avec
l'extension REST Client de VS Code ou le client HTTP d'IntelliJ.

Le message refusé apparaît en `ERROR` dans *Dashboard > Messages*, avec la raison :
`Patient.birthDate: minimum required = 1` (profil `ExampleLabPatient`).

## L'administration web

<https://localhost:8443/oie-webadmin/>, compte `admin`, mot de passe `demo-oru2fhir-admin`. C'est de
l'HTTPS avec un certificat autosigné : le navigateur avertit, il faut accepter.

L'assistant de première connexion d'OIE, qui demande de changer le mot de passe, ne s'ouvre pas :
`oie-init` marque la première connexion du compte comme faite (préférence utilisateur `firstlogin`
à `false`). Pour le garder, mettre `OIE_SKIP_FIRST_LOGIN=false` dans `.env`.

Dans *Channels*, le canal **ORU R01 vers Bundle FHIR**, puis *Source > Edit Transformer* : les sept
étapes FHIR Profile Mapper. Chaque étape montre l'arbre de son profil cible et un mapping par
élément. Les boutons :

- **↻** fait une itération : une entrée par répétition, ou, sur la ligne de la ressource, une
  ressource par item.
- **?** pose une condition.
- **⇄** traduit un code par ConceptMap.

En bas de chaque étape se trouvent :
- la liste des éléments obligatoires non couverts ;
- **Test with a sample message** : coller un exemple, *Run* ;
- le script généré, également visible dans l'onglet *Generated Script*.

## Le canal

| | |
|---|---|
| Source | HTTP Listener, port 8081, chemin `/oru/` ; type de données entrant HL7 v2 |
| Sortie | type FHIR, R4, JSON ; *Validate Outbound* contre les profils de `/fhir-ig` |
| Réponse | le Bundle, renvoyé à l'appelant (destination JavaScript Writer) |
| Traductions | locales (*Terminology Server* `n/a`), sur les ConceptMaps de l'IG |

Les sept étapes ajoutent leurs ressources au même Bundle (*Output* « Add the resource to a Bundle ») :

| Étape | Profil cible | Une ressource par | Points à voir |
|---|---|---|---|
| Bundle | `ExampleOruBundle` | — | En-tête du Bundle : `identifier` (MSH-10), `timestamp` (MSH-7). `type` = `transaction` vient du profil |
| Patient | `ExampleLabPatient` | — | IPP dans la slice `identifier:ipp`, dont le profil fixe le `system`. Noms et téléphones par itération. Sexe traduit (table 0001). `PUT Patient?identifier=…` conditionnel |
| Encounter | Encounter (R4) | — | Condition sur la ressource : seulement s'il y a un PV1. `ifNoneExist` |
| ServiceRequest | `ExampleLabServiceRequest` | OBR | Identifiants PLAC/FILL. Statut ORC-5 traduit (table 0038) via `hl7Preceding(obr, 'ORC')` |
| Specimen | `ExampleLabSpecimen` | SPM | `request` vers le ServiceRequest de son OBR : `fhirRef('sr-' + hl7Ordinal(hl7Preceding(spm, 'OBR', [])))` |
| Observation | `ExampleLabObservation` | OBX (sauf ED) | Code local et LOINC traduit. `value[x]` selon OBX-2, par conditions. Bornes OBX-7 en décimaux exacts. Interprétation (table 0078). Catégorie `laboratory` fixée par le profil |
| DiagnosticReport | `ExampleLabDiagnosticReport` | OBR | `result` itère sur `fhirRefs('obs-' + obrIndex)`. PDF de l'OBX ED (Base64 complété). Catégorie `LAB` fixée par le profil |

Chaque entrée reçoit un `fullUrl` `urn:uuid:…`. Les références entre ressources passent par
`fhirRef(nom)`, où le nom est le *Reference Name* d'une étape précédente (`'patient'`,
`'sr-' + obrIndex`…).

## L'IG d'exemple (`ig/`)

Des ressources de conformité FHIR R4 en JSON, avec la base canonique
`http://example.org/fhir/oru2fhir`. Les profils sont des différentiels, et le plugin génère leurs
snapshots.

| Fichier | Contenu |
|---|---|
| `StructureDefinition-ExampleOruBundle` | Bundle : `type` fixé à `transaction` ; `identifier`, `timestamp`, `entry.fullUrl` et `entry.request` obligatoires |
| `StructureDefinition-ExampleLabPatient` | Patient : slice `identifier:ipp` (system fixé) ; `name`, `gender`, `birthDate` obligatoires |
| `StructureDefinition-ExampleLabServiceRequest` | ServiceRequest : `identifier` et `code` obligatoires |
| `StructureDefinition-ExampleLabSpecimen` | Specimen : `type`, `subject` et `collection.collectedDateTime` obligatoires |
| `StructureDefinition-ExampleLabObservation` | Observation : slice `category:laboratory` (pattern) ; `subject` et `effectiveDateTime` obligatoires |
| `StructureDefinition-ExampleLabDiagnosticReport` | DiagnosticReport : slice `category:lab` (v2-0074 `LAB`) ; `subject` et `effectiveDateTime` obligatoires |
| `CodeSystem-lab-codes` | Codes locaux du laboratoire (GLU, K, NA, CREA, BIOCHEM, CR_PDF, SER…) |
| `ConceptMap-lab-codes-to-loinc` | Codes locaux vers LOINC |
| `ConceptMap-v2-0001-to-gender` | HL7 v2 table 0001 vers `administrative-gender`, avec `unmapped` fixé à `unknown` |
| `ConceptMap-v2-0038-to-request-status` | Table 0038 vers `ServiceRequest.status` |
| `ConceptMap-v2-0078-to-interpretation` | Table 0078 vers `ObservationInterpretation` |
| `ConceptMap-v2-0085-to-observation-status` | Table 0085 vers `Observation.status` |
| `ConceptMap-v2-0123-to-report-status` | Table 0123 vers `DiagnosticReport.status` |

Le conteneur monte `ig/` en `/fhir-ig`, que le canal donne en *Profile Packages*. Pour une vraie IG,
on peut aussi y déposer des paquets `.tgz` (par exemple depuis packages.fhir.org).

Après une modification de `ig/`, redéployer le canal : les ConceptMaps sont relues au déploiement.

## Les messages (`examples/`)

Toutes les données sont fictives.

- `oru-r01-labo.er7` : un patient, un séjour, deux demandes.
  - Un bilan biochimique : 4 résultats numériques, un commentaire et un prélèvement de sérum.
  - Un compte rendu PDF, en OBX de type ED.
  - Le Bundle obtenu a 12 entrées.
- `oru-r01-minimal.er7` : sans séjour ni prélèvement, un seul résultat. Le Bundle a 4 entrées.
- `oru-r01-sans-date-de-naissance.er7` : PID-7 vide. Le profil Patient l'exige, donc le message est
  refusé.

## Arrêter, repartir de zéro

```bash
docker compose down        # arrête ; la base et les canaux sont gardés
docker compose down -v     # supprime aussi les volumes : prochain up = installation neuve
```

Pour réimporter le canal d'origine par-dessus une version modifiée, sans tout effacer :
`OIE_INIT_FORCE=true docker compose up -d`.

## À savoir

- **Heures sans fuseau** : une date et heure HL7 v2 sans fuseau prend celui du conteneur, réglé par
  `TZ` (`Europe/Paris` par défaut).
- **Valeurs par défaut** : les mots de passe et le keystore de `compose.yaml` conviennent à une démo,
  pas à une production.
- **Statut** : la démo montre une preuve de concept, voir le [README du dépôt](../README.md).
