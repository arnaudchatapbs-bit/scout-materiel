// Formes des réponses de l'API (backend/src/main/java/scout/materiel/web/WebApp.java).

export interface Me {
  id: number;
  username: string;
  email: string;
  firstName: string;
  lastName: string;
  displayName: string;
  admin: boolean;
  cartCount: number;
  itemCount: number;
  pendingRequests?: number;
  guestCount?: number;
}

export interface CategoryRef {
  id: number;
  name: string;
}

export interface CategoryCount extends CategoryRef {
  count: number;
}

export interface Item {
  id: number;
  name: string;
  categoryId: number;
  categoryPath: string;
  reference?: string;
  quantity: number;
  location: string;
  comment: string;
  remark?: string;
  isConsumable: boolean;
  active: boolean;
  reservable: boolean;
  reserved: number;
  available: number;
  /** Admin uniquement. */
  pending?: number;
  inCart: number;
  addable: number;
}

export interface ItemGroup {
  heading: string;
  items: Item[];
}

export interface ListPage {
  category?: CategoryRef;
  breadcrumb: CategoryRef[];
  families?: CategoryCount[];
  subcategories?: CategoryCount[];
  groups?: ItemGroup[];
  resultCount?: number;
}

export interface LineSummary {
  requestId: number;
  requesterName: string;
  quantity: number;
  date?: string;
}

export interface ItemPage {
  item: Item;
  breadcrumb: CategoryRef[];
  reservedLines?: LineSummary[];
  pendingLines?: LineSummary[];
}

export interface CartView {
  items: Item[];
  removed: boolean;
}

export interface CartChange {
  message?: string;
  limited: boolean;
  cartCount: number;
}

export type LineStatus = 'en_attente' | 'reservee' | 'refusee' | 'annulee' | 'rendue';
export type RequestStatus = 'en_attente' | 'reservee' | 'terminee' | 'annulee';

export interface RequestLine {
  id: number;
  itemId: number;
  itemName: string;
  itemReference?: string;
  itemLocation?: string;
  quantity: number;
  status: LineStatus;
  adminComment: string;
  reservedAt?: string;
  returnedAt?: string;
  itemAvailable?: number;
  itemQuantity?: number;
}

export interface LoanRequest {
  id: number;
  requesterId: number;
  requesterName: string;
  requesterEmail: string;
  createdAt: string;
  status: RequestStatus;
  lines: RequestLine[];
}

export interface CategoryRow {
  id: number;
  name: string;
  parentId?: number;
  path: string;
  depth: number;
  consumable: boolean;
  inheritedConsumable: boolean;
  itemCount: number;
  childCount: number;
}

export interface Account {
  id: number;
  username: string;
  email: string;
  firstName: string;
  lastName: string;
  displayName: string;
  admin: boolean;
  active: boolean;
  hasPassword: boolean;
  dateJoined: string;
  lastLogin?: string;
}

export interface ImportReport {
  dryRun: boolean;
  categoriesCreated: number;
  created: number;
  updated: number;
  skipped: number;
  merged: string[];
  doubtful: string[];
  warnings: string[];
}

export interface NotificationSettings {
  /** Adresses saisies dans Gestion › Réglages (vide : comportement par défaut). */
  emails: string;
  /** Destinataires effectifs des e-mails admin. */
  recipients: string[];
  source: 'reglage' | 'env' | 'comptes';
}
